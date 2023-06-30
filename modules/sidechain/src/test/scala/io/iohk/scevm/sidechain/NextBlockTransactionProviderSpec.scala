package io.iohk.scevm.sidechain

import cats.data.NonEmptyVector
import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain.LeaderSlotEvent.KeySet
import io.iohk.scevm.domain.{
  Address,
  BlockContext,
  EpochPhase,
  Nonce,
  ObftBlock,
  ObftGenesisAccount,
  ObftGenesisData,
  SignedTransaction,
  Slot,
  Token,
  TransactionType02,
  UInt256
}
import io.iohk.scevm.exec.vm.{IOEvmCall, WorldType}
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.ledger.{GetByNumberServiceStub, NonceProviderImpl}
import io.iohk.scevm.sidechain.NewBlockTransactionsProvider.SlotBasedMempool
import io.iohk.scevm.sidechain.SidechainFixtures.SidechainEpochDerivationStub
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction
import io.iohk.scevm.sidechain.certificate.{CheckpointSigner, CommitteeHandoverSigner, MerkleRootSigner}
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.metrics.NoopSidechainMetrics
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.testing.SidechainGenerators.validLeaderCandidateGen
import io.iohk.scevm.sidechain.transactions.OutgoingTransaction
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sync.NextBlockTransactions
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.{IOSupport, TestCoreConfigs}
import io.iohk.scevm.trustlesssidechain.cardano.{
  AssetName,
  MainchainAddress,
  MainchainBlockNumber,
  MainchainSlot,
  MainchainTxHash,
  PolicyId
}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import io.janstenpickle.trace4cats.inject.Trace
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration._

class NextBlockTransactionProviderSpec
    extends AsyncWordSpec
    with ScalaFutures
    with IOSupport
    with Matchers
    with EitherValues
    with IntegrationPatience
    with TableDrivenPropertyChecks {

  private val epochDerivation = new SidechainEpochDerivationStub[IO](slotsInEpoch = 12, stabilityParameter = 1)

  "should include both cross-chain transactions and regular transactions when running as sidechain validator" in {
    val bridgeContract       = new FakeBridgeContractStub(Slot(1))
    val fixture              = createFixture(Map.empty)
    val incomingCrossChainTx = SidechainGenerators.validIncomingCrossChainTransactionGen.sample.get
    val txProvider =
      createTransactionProvider(
        fixture,
        List(regularTransaction),
        List(incomingCrossChainTx),
        Some(ECDSA.PrivateKey.Zero),
        bridgeContract
      )
    val transactions =
      txProvider
        .getNextTransactions(Slot(1), KeySet.ECDSAZero, fixture.genesisBlock.header)
        .ioValue
        .value

    // cross-chain transactions should be included first
    transactions.map(t => t.transaction.receivingAddress -> t.transaction.value) shouldBe List(
      Some(incomingCrossChainTx.recipient)            -> incomingCrossChainTx.value,
      regularTransaction.transaction.receivingAddress -> regularTransaction.transaction.value
    )
  }

  "should include committee handover signature transaction, in Handover phase only, when running as sidechain validator" in {
    val fixture              = createFixture(Map.empty)
    val incomingCrossChainTx = SidechainGenerators.validIncomingCrossChainTransactionGen.sample.get

    val slots = Table(
      ("slot", "epochPhase"),
      (0 until epochDerivation.slotsInEpoch)
        .map(Slot(_))
        .map(s => (s, epochDerivation.getSidechainEpochPhase(s))): _*
    )
    forAll(slots) { (slot: Slot, phase: EpochPhase) =>
      val bridgeContract = new FakeBridgeContractStub(slot)
      val txProvider =
        createTransactionProvider(
          fixture,
          List(regularTransaction),
          List(incomingCrossChainTx),
          Some(ECDSA.PrivateKey.Zero),
          bridgeContract
        )
      val transactions =
        txProvider
          .getNextTransactions(slot, KeySet.ECDSAZero, fixture.genesisBlock.header)
          .ioValue
          .value

      val alwaysExpected = List(
        Some(incomingCrossChainTx.recipient)            -> incomingCrossChainTx.value,
        regularTransaction.transaction.receivingAddress -> regularTransaction.transaction.value
      )
      val expectedInHandover = List(
        Some(FakeBridgeContractStub.signTransactionAddress) -> FakeBridgeContractStub.signTransactionValue
      )
      val expected =
        if (phase == EpochPhase.Handover) expectedInHandover ++ alwaysExpected
        else alwaysExpected

      transactions.map(t => t.transaction.receivingAddress -> t.transaction.value) shouldBe expected
    }
  }

  "should not include committee handover signature transaction when running as sidechain validator outside of handover phase" in {
    val slotInHandoverPhase = Slot(epochDerivation.numberOfSlotsInRegularPhase - 1)
    val fixture             = createFixture(Map.empty)
    val incomingCrossChainTx = ValidIncomingCrossChainTransaction(
      recipient = Address(Hex.decodeAsArrayUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98")),
      value = Token(-500),
      txId = MainchainTxHash(
        Hex.decodeUnsafe("EBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1")
      ),
      MainchainBlockNumber(38)
    )
    val bridgeContract = new FakeBridgeContractStub(slotInHandoverPhase)
    val txProvider =
      createTransactionProvider(
        fixture,
        List.empty,
        List(incomingCrossChainTx),
        Some(ECDSA.PrivateKey.Zero),
        bridgeContract
      )
    val transactions = txProvider
      .getNextTransactions(
        slotInHandoverPhase,
        KeySet.ECDSAZero,
        fixture.genesisBlock.header
      )
      .ioValue
      .value

    // sign committee transaction should be before cross-chain transactions
    transactions.map(t => t.transaction.receivingAddress -> t.transaction.value) shouldBe List(
      Some(incomingCrossChainTx.recipient) -> incomingCrossChainTx.value
    )
  }

  "should propagate error from SignTransactionProvider" in {
    val slotInEpoch0HandoverPhase =
      Slot(epochDerivation.numberOfSlotsInRegularPhase + epochDerivation.numberOfSlotsInClosedPhase + 1)
    val fixture = createFixture(Map.empty)
    // There will be DataNotAvailable for sidechain epoch 1
    val bridgeContract = new FakeBridgeContractStub(slotInEpoch0HandoverPhase)
    val txProvider =
      createTransactionProvider(
        fixture,
        List.empty,
        List.empty,
        Some(ECDSA.PrivateKey.Zero),
        bridgeContract,
        Some(SidechainEpoch(1))
      )
    val failure =
      txProvider
        .getNextTransactions(
          slotInEpoch0HandoverPhase,
          KeySet.ECDSAZero,
          fixture.genesisBlock.header
        )
        .ioValue
        .left
        .value

    failure shouldBe "Couldn't get handover signature transaction, because of 'Couldn't get committee for epoch 1, because of 'Currently no data is available on the main chain for this epoch (mainchain = 0, sidechain = 1)''"
  }

  private def createTransactionProvider(
      fixture: Fixture,
      regularTransaction: List[SignedTransaction],
      incomingCrossChainTx: List[ValidIncomingCrossChainTransaction],
      validatorPrivateKey: Option[ECDSA.PrivateKey],
      bridgeContract: FakeBridgeContractStub,
      epochWithDataNotAvailable: Option[SidechainEpoch] = None
  ): NextBlockTransactions[IO] =
    createTransactionProvider(
      fixture,
      new SlotBasedMempool[IO] {
        override def getAliveElements(slot: Slot): IO[List[SignedTransaction]] = IO.pure(regularTransaction)
      },
      incomingCrossChainTx,
      validatorPrivateKey,
      bridgeContract,
      epochWithDataNotAvailable
    )

  // scalastyle:off method.length
  private def createTransactionProvider(
      fixture: Fixture,
      mempool: SlotBasedMempool[IO],
      incomingCrossChainTx: List[ValidIncomingCrossChainTransaction],
      validatorPrivateKey: Option[ECDSA.PrivateKey],
      bridgeContract: FakeBridgeContractStub,
      epochWithDataNotAvailable: Option[SidechainEpoch]
  ): NextBlockTransactions[IO] = {
    implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
    validatorPrivateKey match {
      case Some(_) =>
        val nonceProvider = new NonceProviderImpl(
          getAllFromMemPool = mempool.getAliveElements(Slot(0)),
          blockchainConfig = TestCoreConfigs.blockchainConfig
        )
        val sidechainParams = SidechainFixtures.sidechainParams
        val leaderCandidate = validLeaderCandidateGen[ECDSA].sample.get
        val committeeProvider: SidechainCommitteeProvider[IO, ECDSA] = epoch =>
          if (epochWithDataNotAvailable.contains(epoch))
            IO.pure(Left(ElectionFailure.dataNotAvailable(0, epoch.number)))
          else IO.pure(Right(CommitteeElectionSuccess(NonEmptyVector.of(leaderCandidate))))
        val signedTxProvider =
          new HandoverTransactionProviderImpl[IO, ECDSA](
            nonceProvider,
            committeeProvider,
            bridgeContract,
            sidechainParams,
            new CommitteeHandoverSigner[IO]
          )
        val signedIncomingTransactionService = new IncomingTransactionsServiceImpl[IO](
          newIncomingTransactionsProvider = (_: WorldType) => IO.pure(incomingCrossChainTx),
          bridgeContract = bridgeContract
        )
        NewBlockTransactionsProvider.validator[IO, ECDSA](
          TestCoreConfigs.blockchainConfig,
          mempool,
          fixture.worldBuilder,
          signedTxProvider,
          signedIncomingTransactionService,
          NoopSidechainMetrics[IO](),
          epochDerivation
        )
      case None => NewBlockTransactionsProvider.passive(mempool)
    }

  }
  // scalastyle:on method.length

  case class Fixture(genesisBlock: ObftBlock, worldBuilder: WorldStateBuilderImpl[IO], systemTime: SystemTime[IO])

  private def createFixture(alloc: Map[Address, ObftGenesisAccount]) = {
    val dataSource   = EphemDataSource()
    val stateStorage = new ArchiveStateStorage(new NodeStorage(dataSource))
    val codeStorage  = new EvmCodeStorageImpl(dataSource)

    val block = ObftGenesisLoader
      .load[IO](codeStorage, stateStorage)(
        ObftGenesisData(
          coinbase = Address("0x0000000000000000000000000000000000000000"),
          gasLimit = 8000000,
          timestamp = 0L.millisToTs,
          alloc = alloc,
          TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce
        )
      )
      .ioValue

    val worldBuilder = new WorldStateBuilderImpl(
      codeStorage,
      new GetByNumberServiceStub(List(block)),
      stateStorage,
      TestCoreConfigs.blockchainConfig
    )

    val st = SystemTime.liveF[IO].unsafeRunSync()
    Fixture(block, worldBuilder, st)
  }

  def baseMainchainConfig: CardanoFollowerConfig = CardanoFollowerConfig(
    stabilityParameter = 2160,
    firstEpochTimestamp = 0.millisToTs,
    firstEpochNumber = 0,
    firstSlot = MainchainSlot(0),
    epochDuration = 1900.seconds,
    slotDuration = 1.seconds,
    activeSlotCoeff = BigDecimal("0.05"),
    committeeCandidateAddress = MainchainAddress("dummy"),
    fuelMintingPolicyId = PolicyId.empty,
    fuelAssetName = AssetName.empty,
    merkleRootNftPolicyId = PolicyId.empty,
    committeeNftPolicyId = PolicyId.empty,
    checkpointNftPolicyId = PolicyId.empty
  )

  class FakeBridgeContractStub(expectedSlot: Slot)
      extends IncomingTransactionsService.BridgeContract[IOEvmCall]
      with HandoverTransactionProvider.BridgeContract[IOEvmCall, ECDSA] {

    override def createUnlockTransaction(
        mTx: ValidIncomingCrossChainTransaction,
        currentSlot: Slot
    ): IOEvmCall[SystemTransaction] =
      IOEvmCall(world => IO.pure(mkTransaction(mTx.recipient, mTx.value.value, world.blockContext)))

    private def mkTransaction(receivingAddress: Address, value: BigInt, blockContext: BlockContext) =
      SystemTransaction(
        chainId = ChainId(0),
        gasLimit = blockContext.gasLimit,
        receivingAddress = receivingAddress,
        value = value,
        ByteString.empty
      )

    override def createSignTransaction(
        handoverSignature: ECDSASignature,
        checkpoint: Option[CheckpointSigner.SignedCheckpoint],
        txsBatchMRH: Option[MerkleRootSigner.SignedMerkleRootHash[ECDSASignature]]
    ): IOEvmCall[SystemTransaction] =
      IOEvmCall(world =>
        IO.delay {
          assert(world.blockContext.slotNumber == expectedSlot)
          mkTransaction(
            FakeBridgeContractStub.signTransactionAddress,
            FakeBridgeContractStub.signTransactionValue,
            world.blockContext
          )
        }
      )

    override def getTransactionsInBatch(batch: UInt256): IOEvmCall[Seq[OutgoingTransaction]] =
      IOEvmCall.pure(Seq.empty)

    override def getPreviousMerkleRoot: IOEvmCall[Option[RootHash]] = IOEvmCall.pure(Option.empty)

    override def getTransactionsMerkleRootHash(epoch: SidechainEpoch): IOEvmCall[Option[RootHash]] =
      IOEvmCall.pure(None)
  }

  object FakeBridgeContractStub {
    val signTransactionAddress: Address = Address(13)
    val signTransactionValue: BigInt    = 31
  }

  private val regularTransaction: SignedTransaction = SignedTransaction.sign(
    TransactionType02(
      chainId = 1,
      nonce = Nonce.Zero,
      maxPriorityFeePerGas = 0,
      maxFeePerGas = 1000000000,
      gasLimit = 123457,
      receivingAddress = Address("0x0000000000000000000000000000000000000000"),
      value = 0,
      payload = ByteString.empty,
      accessList = Nil
    ),
    ECDSA.PrivateKey.fromHexUnsafe("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"),
    None
  )
}
