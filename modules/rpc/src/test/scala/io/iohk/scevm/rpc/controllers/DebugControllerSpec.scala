package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import cats.syntax.all._
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.consensus.domain.BlocksBranch
import io.iohk.scevm.consensus.pos.ConsensusService.BetterBranch
import io.iohk.scevm.consensus.pos.{BlockPreExecution, BranchExecutionImpl, CurrentBranch, ObftBlockExecutionImpl}
import io.iohk.scevm.consensus.validators.PostExecutionValidatorImpl
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.db.storage.BranchProvider.BranchRetrievalError
import io.iohk.scevm.db.storage._
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.exec.vm.{EVM, StorageType, VM, WorldType}
import io.iohk.scevm.ledger.blockgeneration.BlockGeneratorImpl
import io.iohk.scevm.ledger.{
  BlockRewardCalculator,
  BlockRewarder,
  FixedRewardAddressProvider,
  TransactionExecutionImpl,
  _
}
import io.iohk.scevm.metrics.instruments.Counter
import io.iohk.scevm.mpt.EphemeralMptStorage
import io.iohk.scevm.rpc.controllers.DebugController.{
  TraceParameters,
  TraceTransactionRequest,
  TraceTransactionResponse
}
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAsyncWordSpec

import scala.concurrent.duration.DurationInt

class DebugControllerSpec
    extends FixtureAsyncWordSpec
    with Matchers
    with AsyncDataSourceFixture
    with AsyncMockFactory
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "DebugController" when {
    "traceTransaction() is called" should {
      "return error with the tracer is invalid" in { fixture =>
        val debugController = fixture.debugController

        val result = debugController
          .traceTransaction(
            TraceTransactionRequest(
              fixture.registeredTransactionHash1,
              TraceParameters(
                tracer = """
                         |{
                         |  "fault": function(log, db) {},
                         |  "result": function(ctx, db) { return true; }
                         |}
                         |""".stripMargin,
                timeout = None
              )
            )
          )
          .ioValue

        result shouldBe Left(
          JsonRpcError(
            400,
            "Javascript-based tracing must define `step` function or `enter` and `exit` functions (or all).",
            None
          )
        )
      }

      "return transaction not found if transaction is not part of a block" in { fixture =>
        val debugController = fixture.debugController
        val hashString      = "98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb"

        val result = debugController
          .traceTransaction(
            TraceTransactionRequest(
              TransactionHash(Hex.decodeUnsafe(hashString)),
              TraceParameters(
                tracer = """
                  |{
                  |  "step": function(log, db) {},
                  |  "fault": function(log, db) {},
                  |  "result": function(ctx, db) { return [ true ]; }
                  |}
                  |""".stripMargin,
                timeout = Some(5.seconds)
              )
            )
          )
          .ioValue

        result shouldBe Left(
          JsonRpcError(404, s"Transaction or sender of transaction not found for hash $hashString", None)
        )
      }

      "return transaction not found if MptState is corrupted" in { fixture =>
        val debugController = fixture.debugControllerNoState
        val hashString      = "98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb"

        val result = debugController
          .traceTransaction(
            TraceTransactionRequest(
              TransactionHash(Hex.decodeUnsafe(hashString)),
              TraceParameters(
                tracer = """
                         |{
                         |  "step": function(log, db) {},
                         |  "fault": function(log, db) {},
                         |  "result": function(ctx, db) { return [ true ]; }
                         |}
                         |""".stripMargin,
                timeout = Some(5.seconds)
              )
            )
          )
          .ioValue

        result shouldBe Left(
          JsonRpcError(404, s"Transaction or sender of transaction not found for hash $hashString", None)
        )
      }

      "return a valid response if the transaction exists in the blockchain" in { fixture =>
        val debugController = fixture.debugController

        val result = debugController
          .traceTransaction(
            TraceTransactionRequest(
              fixture.registeredTransactionHash1,
              TraceParameters(
                tracer = """
                  |{
                  |  "step": function(log, db) {},
                  |  "fault": function(log, db) {},
                  |  "result": function(ctx, db) { return true; }
                  |}
                  |""".stripMargin,
                timeout = None
              )
            )
          )
          .ioValue

        result shouldBe Right("true")
      }

      "handle the scenario where the tx to be traced is impacted by txs present in the same block" in { fixture =>
        val debugController = fixture.debugController

        def getBalance(transactionHash: TransactionHash): Either[JsonRpcError, TraceTransactionResponse] =
          debugController
            .traceTransaction(
              TraceTransactionRequest(
                transactionHash,
                TraceParameters(
                  tracer = s"""{
                            |  "step": function(log, db) {},
                            |  fault: function(log, db) {},
                            |  result: function(ctx, db) {
                            |    return [ db.getBalance("${fixture.fromAddress}"), db.getBalance("${fixture.toAddress}") ]
                            |  }
                            |}""".stripMargin,
                  None
                )
              )
            )
            .ioValue

        // check for the seconds tx first!
        getBalance(fixture.registeredTransactionHash2) shouldBe Right(TraceTransactionResponse("[70,30]"))
        getBalance(fixture.registeredTransactionHash1) shouldBe Right(TraceTransactionResponse("[90,10]"))
      }

      "handle the scenario where the tx to be traced is impacted by txs present in a previous block" in { fixture =>
        val debugController = fixture.debugController

        def getBalance(transactionHash: TransactionHash): Either[JsonRpcError, TraceTransactionResponse] =
          debugController
            .traceTransaction(
              TraceTransactionRequest(
                transactionHash,
                TraceParameters(
                  tracer = s"""{
                            |  "step": function(log, db) {},
                            |  fault: function(log, db) {},
                            |  result: function(ctx, db) {
                            |    return [ db.getBalance("${fixture.fromAddress}"), db.getBalance("${fixture.toAddress}") ]
                            |  }
                            |}""".stripMargin,
                  None
                )
              )
            )
            .ioValue

        // on block 3 (depending on tx on block 1)
        getBalance(fixture.registeredTransactionHash3) shouldBe Right(TraceTransactionResponse("[40,60]"))
      }
    }
  }

  case class FixtureParam(
      debugController: DebugController[IO],
      debugControllerNoState: DebugController[IO],
      registeredTransactionHash1: TransactionHash,
      registeredTransactionHash2: TransactionHash,
      registeredTransactionHash3: TransactionHash,
      fromAddress: Address,
      toAddress: Address
  )

  // scalastyle:off method.length
  override def initFixture(dataSource: DataSource): FixtureParam = {

    // Inputs
    val (privateKey, publicKey) = ecdsaKeyPairGen.sample.get
    val rewardAddress           = Address(0)
    val receiverAddress         = Address(1)
    val senderAddress           = Address.fromPublicKey(publicKey)

    val signedTransaction1 =
      SignedTransaction.sign(
        LegacyTransaction(Nonce(0), 0, 1000000, receiverAddress, 10, ByteString.empty),
        privateKey,
        None
      )

    val signedTransaction2 =
      SignedTransaction.sign(
        LegacyTransaction(Nonce(1), 0, 1000000, receiverAddress, 20, ByteString.empty),
        privateKey,
        None
      )
    val signedTransaction3 =
      SignedTransaction.sign(
        LegacyTransaction(Nonce(2), 0, 1000000, receiverAddress, 30, ByteString.empty),
        privateKey,
        None
      )

    val genesisData = ObftGenesisData(
      Address("0x0000000000000000000000000000000000000000"),
      BigInt("7A1200", 16),
      UnixTimestamp.fromSeconds(BigInt("5FA34080", 16).toLong),
      Map(
        senderAddress   -> ObftGenesisAccount(UInt256(BigInt(100)), None, None, Map.empty),
        receiverAddress -> ObftGenesisAccount(UInt256(BigInt(0)), None, None, Map.empty)
      ),
      Nonce.Zero
    )

    // Building
    val blockchainConfig = TestCoreConfigs.blockchainConfig

    val nodeStorage                = new NodeStorage(dataSource)
    val stateStorage               = StateStorage(nodeStorage)
    val evmCodeStorage             = new EvmCodeStorageImpl(dataSource)
    val blockchainStorage          = BlockchainStorage.unsafeCreate[IO](dataSource)
    val transactionMappingStorage  = new StableTransactionMappingStorageImpl[IO](dataSource)
    val stableNumberMappingStorage = new StableNumberMappingStorageImpl[IO](dataSource)
    val receiptStorage             = ReceiptStorage.unsafeCreate[IO](dataSource)

    val transactionBlockService            = new GetTransactionBlockServiceImpl[IO](blockchainStorage, transactionMappingStorage)
    val getByNumberService                 = new GetByNumberServiceImpl(blockchainStorage, stableNumberMappingStorage)
    val vm: VM[IO, WorldType, StorageType] = EVM[WorldType, StorageType]()
    val signedTransactionValidator         = SignedTransactionValidatorImpl
    val rewardAddressProvider              = new FixedRewardAddressProvider(rewardAddress)
    val txPreExecution                     = BlockPreExecution.noop[IO]
    val transactionExecution =
      new TransactionExecutionImpl(vm, signedTransactionValidator, rewardAddressProvider, blockchainConfig)
    val worldStateBuilder =
      new WorldStateBuilderImpl[IO](evmCodeStorage, getByNumberService, stateStorage, blockchainConfig)
    val blockRewardCalculator =
      BlockRewardCalculator(blockchainConfig.monetaryPolicyConfig.blockReward, rewardAddressProvider)
    val blockRewarder = BlockRewarder.payBlockReward[IO](blockchainConfig, blockRewardCalculator) _
    val blockPreparation =
      new BlockPreparationImpl[IO](
        txPreExecution,
        transactionExecution,
        blockRewarder,
        worldStateBuilder
      )
    val blockGenerator         = new BlockGeneratorImpl[IO](blockPreparation)
    val postExecutionValidator = new PostExecutionValidatorImpl[IO]
    val blockExecution =
      new ObftBlockExecutionImpl[IO](
        txPreExecution,
        transactionExecution,
        postExecutionValidator,
        blockRewarder,
        Counter.noop
      )

    def createBranchProvider(chain: List[ObftHeader]) = new BranchProvider[IO] {
      override def findSuffixesTips(parent: BlockHash): IO[Seq[ObftHeader]] = ???

      override def getChildren(parent: BlockHash): IO[Seq[ObftHeader]] = ???

      override def fetchChain(
          from: ObftHeader,
          to: ObftHeader
      ): IO[Either[BranchRetrievalError, List[ObftHeader]]] =
        chain
          .asRight[BranchRetrievalError]
          .pure[IO]
    }

    val blocksReader: BlocksReader[IO] = BlockchainStorage.unsafeCreate[IO](dataSource)
    val genesisBlock                   = ObftGenesisLoader.load[IO](evmCodeStorage, stateStorage)(genesisData).ioValue

    implicit val currentBranch: Signal[IO, CurrentBranch] =
      Signal.constant[IO, CurrentBranch](CurrentBranch(genesisBlock.header))

    (for {
      genesisBlock <- ObftGenesisLoader.load[IO](evmCodeStorage, stateStorage)(genesisData)
      block1 <- blockGenerator
                  .generateBlock(
                    genesisBlock.header,
                    Seq(signedTransaction1, signedTransaction2),
                    Slot(genesisBlock.header.slotNumber.number + 1),
                    genesisBlock.header.unixTimestamp.add(1.second),
                    publicKey,
                    privateKey
                  )
      block2 <- blockGenerator
                  .generateBlock(
                    block1.header,
                    Seq.empty,
                    Slot(block1.header.slotNumber.number + 1),
                    block1.header.unixTimestamp.add(1.second),
                    publicKey,
                    privateKey
                  )
      block3 <- blockGenerator
                  .generateBlock(
                    block2.header,
                    Seq(signedTransaction3),
                    Slot(block2.header.slotNumber.number + 1),
                    block2.header.unixTimestamp.add(1.second),
                    publicKey,
                    privateKey
                  )
      _ <- currentBranch.get
             .map(branch => branch.update(BetterBranch(Vector(genesisBlock), block3.header)))

      _ <- blockchainStorage.insertBlock(genesisBlock)
      _ <- blockchainStorage.insertBlock(block1)
      _ <- blockchainStorage.insertBlock(block2)
      _ <- blockchainStorage.insertBlock(block3)
      _ = block1.body.transactionList.zipWithIndex.foreach { case (transaction, transactionIndex) =>
            transactionMappingStorage
              .put(transaction.hash, TransactionLocation(block1.hash, transactionIndex))
              .commit()
          }
      _ = block3.body.transactionList.zipWithIndex.foreach { case (transaction, transactionIndex) =>
            transactionMappingStorage
              .put(transaction.hash, TransactionLocation(block3.hash, transactionIndex))
              .commit()
          }
      branchProvider = createBranchProvider(List(block1.header, block2.header, block3.header))
      branchExecution =
        new BranchExecutionImpl[IO](
          blockExecution,
          blockchainStorage,
          branchProvider,
          receiptStorage,
          worldStateBuilder
        )
      _ <- branchExecution.execute(BlocksBranch(genesisBlock, Vector(block1, block2, block3), block3))
    } yield ()).ioValue

    val defaultTimeout = 5.seconds
    val debugController: DebugController[IO] = new DebugControllerImpl(
      transactionBlockService,
      blocksReader,
      blockchainConfig,
      worldStateBuilder,
      defaultTimeout
    )

    val stateStorageMock = mock[StateStorage]
    (() => stateStorageMock.archivingStorage).expects().returning(new EphemeralMptStorage())

    val worldStateBuilderNoState =
      new WorldStateBuilderImpl[IO](evmCodeStorage, getByNumberService, stateStorageMock, blockchainConfig)

    val debugControllerNoState: DebugController[IO] = new DebugControllerImpl(
      transactionBlockService,
      blocksReader,
      blockchainConfig,
      worldStateBuilderNoState,
      defaultTimeout
    )

    FixtureParam(
      debugController,
      debugControllerNoState,
      signedTransaction1.hash,
      signedTransaction2.hash,
      signedTransaction3.hash,
      senderAddress,
      receiverAddress
    )
  }
  // scalastyle:on method.length
}
