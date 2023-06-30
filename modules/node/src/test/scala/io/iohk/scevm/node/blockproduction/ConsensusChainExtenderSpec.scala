package io.iohk.scevm.node.blockproduction

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.consensus.metrics.TimeToFinalizationTracker
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranch}
import io.iohk.scevm.consensus.testing.BetterBranchGenerators
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{ArchiveStateStorage, BranchProvider, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain.{
  BlockContext,
  LeaderSlotEvent,
  NotLeaderSlotEvent,
  ObftBlock,
  ObftHeader,
  SignedTransaction,
  Slot
}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.BlockImportService.ImportedBlock
import io.iohk.scevm.ledger.blockgeneration.{BlockGenerator, BlockGeneratorImpl}
import io.iohk.scevm.ledger.{BlockImportService, BlockPreparation, PreparedBlock}
import io.iohk.scevm.metrics.instruments.Histogram
import io.iohk.scevm.network.metrics.NoOpBlockPropagationMetrics
import io.iohk.scevm.node.blockproduction.ChainExtender.ChainExtensionError.{
  CannotImportOwnBlock,
  ConsensusError,
  NoTransactionsError
}
import io.iohk.scevm.node.blockproduction.ChainExtender.ChainExtensionResult
import io.iohk.scevm.node.blockproduction.ChainExtender.ChainExtensionResult.ChainNotExtended
import io.iohk.scevm.testing._
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp._
import io.janstenpickle.trace4cats.inject.Trace
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ConsensusChainExtenderSpec
    extends AnyWordSpec
    with IOSupport
    with ScalaFutures
    with NormalPatience
    with ScalaCheckPropertyChecks
    with Matchers {

  "ConsensusBlockProducer" when {

    val keyPair = {
      val (prvKey, pubKey) = CryptoGenerators.ecdsaKeyPairGen.sample.get
      LeaderSlotEvent.KeySet(pubKey, prvKey, Some(prvKey))
    }
    val genesis: ObftBlock = BlockGenerators.obftBlockGen.sample.get
    val worldState: InMemoryWorldState = {
      val dataSource: EphemDataSource = EphemDataSource()
      val stateStorage = new ArchiveStateStorage(
        new NodeStorage(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
      )
      val codeStorage =
        new EvmCodeStorageImpl(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
      InMemoryWorldState(
        evmCodeStorage = codeStorage,
        mptStorage = stateStorage.archivingStorage,
        getBlockHashByNumber = (_, _) => Some(genesis.hash),
        accountStartNonce = TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce,
        stateRootHash = genesis.header.stateRoot,
        noEmptyAccounts = EvmConfig.forBlock(genesis.number.next, TestCoreConfigs.blockchainConfig).noEmptyAccounts,
        blockContext = BlockContext.from(genesis.header)
      )
    }

    val leaderSlotEvent = LeaderSlotEvent(Slot(1), 0.millisToTs, keyPair)

    "leading" should {
      "extend current branch with new block that include provided transactions" in {
        val newTransactions = List(TransactionGenerators.signedTxGen().sample.get)

        val service = chainExtender(transactions = Right(newTransactions))

        val result = service.extendChainIfLeader(leaderSlotEvent, CurrentBranch(genesis.header)).ioValue

        result shouldBe Right(ChainExtensionResult.ChainExtended)
      }

      "fail if NextBlockTransactions return Left" in {
        val nextTransactionsErrMsg = "some reason"
        val service                = chainExtender(transactions = Left(nextTransactionsErrMsg))

        val result = service.extendChainIfLeader(leaderSlotEvent, CurrentBranch(genesis.header)).ioValue

        result shouldBe Left(NoTransactionsError(nextTransactionsErrMsg, leaderSlotEvent.slot))
      }

      "fail if block import fails" in {
        val newTransactions = List(TransactionGenerators.signedTxGen().sample.get)

        val service = chainExtender(
          transactions = Right(newTransactions),
          blockImportService = failingBlockImportServiceStub
        )

        val result = service.extendChainIfLeader(leaderSlotEvent, CurrentBranch(genesis.header)).ioValue

        result shouldBe Left(CannotImportOwnBlock(leaderSlotEvent.slot))
      }

      val notConnectedConsensusServiceStubs =
        Table("consensusService", disconnectedBranchConsensusServiceStub, forkedBeyondStableBranchConsensusServiceStub)

      "fail if ConsensusService doesn't return ConnectedBranch" in {
        forAll(notConnectedConsensusServiceStubs) { (consensusService: ConsensusService[IO]) =>
          val newTransactions = List(TransactionGenerators.signedTxGen().sample.get)

          val service = chainExtender(
            transactions = Right(newTransactions),
            consensusService = consensusService
          )

          val result = service.extendChainIfLeader(leaderSlotEvent, CurrentBranch(genesis.header)).ioValue

          result.isLeft shouldBe true
          result.swap.toOption.get shouldBe a[ConsensusError]
        }

      }
    }

    "not leading" should {
      "not extend current branch with blocks" in {
        val newTransactions = List(TransactionGenerators.signedTxGen().sample.get)
        val service = chainExtender(
          transactions = Right(newTransactions),
          blockGenerator = nonLeadingBlockGenerator
        )

        val result =
          service.extendChainIfLeader(NotLeaderSlotEvent(Slot(1), 0.millisToTs), CurrentBranch(genesis.header)).ioValue

        result shouldBe Right(ChainNotExtended)
      }

    }

    def chainExtender(
        transactions: Either[String, List[SignedTransaction]],
        blockGenerator: BlockGenerator[IO] = new BlockGeneratorImpl[IO](blockPreparation),
        blockImportService: BlockImportService[IO] = blockImportServiceStub,
        consensusService: ConsensusService[IO] = consensusServiceStub
    ): ConsensusChainExtender[IO] = {
      implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

      new ConsensusChainExtender[IO](
        blockGenerator,
        (_, _, _) => IO.pure(transactions),
        blockImportService,
        consensusService,
        TimeToFinalizationTracker.noop[IO],
        NoOpBlockPropagationMetrics[IO](),
        _ => IO.unit,
        _ => IO.unit
      )
    }

    def failingBlockImportServiceStub: BlockImportService[IO] =
      new BlockImportService[IO] {
        override def importBlock(preValidatedBlock: BlockImportService.PreValidatedBlock): IO[Option[ImportedBlock]] =
          IO.pure(None)

        override def importAndValidateBlock(
            unvalidatedBlock: BlockImportService.UnvalidatedBlock
        ): IO[Option[ImportedBlock]] = IO.pure(None)
      }

    def blockPreparation: BlockPreparation[IO] = new BlockPreparation[IO] {
      override def prepareBlock(
          blockContext: BlockContext,
          transactionList: Seq[SignedTransaction],
          parent: ObftHeader
      ): IO[PreparedBlock] =
        IO.pure(
          PreparedBlock(transactionList, BlockExecutionResult(worldState), ByteString.empty, worldState)
        )
    }

    def blockImportServiceStub: BlockImportService[IO] = new BlockImportService[IO] {
      override def importBlock(preValidatedBlock: BlockImportService.PreValidatedBlock): IO[Option[ImportedBlock]] =
        IO.pure(Some(ImportedBlock(preValidatedBlock.block, fullyValidated = true)))

      override def importAndValidateBlock(
          unvalidatedBlock: BlockImportService.UnvalidatedBlock
      ): IO[Option[ImportedBlock]] =
        IO.pure(Some(ImportedBlock(unvalidatedBlock.block, fullyValidated = true)))
    }

    def consensusServiceStub: ConsensusService[IO] = new ConsensusService[IO] {
      override def resolve(
          importedBlock: ImportedBlock,
          currentBranch: CurrentBranch
      ): IO[ConsensusService.ConsensusResult] = {
        val maybeBetterGen = Gen.option(BetterBranchGenerators.singleElementBetterBranchGen(importedBlock.header))
        IO.pure(ConsensusService.ConnectedBranch(importedBlock.block, maybeBetterGen.sample.get))
      }
    }

    def forkedBeyondStableBranchConsensusServiceStub: ConsensusService[IO] =
      (importedBlock: ImportedBlock, _) => IO.pure(ConsensusService.ForkedBeyondStableBranch(importedBlock.block))

    def disconnectedBranchConsensusServiceStub: ConsensusService[IO] = (importedBlock: ImportedBlock, _) =>
      IO.pure(
        ConsensusService.DisconnectedBranchMissingParent(
          importedBlock.block,
          BranchProvider.MissingParent(
            importedBlock.header.parentHash,
            importedBlock.block.number.previous,
            importedBlock.header
          )
        )
      )

    def nonLeadingBlockGenerator: BlockGenerator[IO] = new BlockGenerator[IO] {
      override def generateBlock(
          parent: ObftHeader,
          transactionList: Seq[SignedTransaction],
          slotNumber: Slot,
          timestamp: SystemTime.UnixTimestamp,
          validatorPubKey: ECDSA.PublicKey,
          validatorPrvKey: ECDSA.PrivateKey
      ): IO[ObftBlock] =
        fail("block generation should never start for a non leading block")
    }
  }

}
