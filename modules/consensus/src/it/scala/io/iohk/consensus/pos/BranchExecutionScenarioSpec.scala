package io.iohk.consensus.pos

import cats.data.StateT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.{BlockchainConfig, StandaloneBlockchainConfig}
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.consensus.domain.BlocksBranch
import io.iohk.scevm.consensus.pos.{BlockPreExecution, BranchExecutionImpl, ObftBlockExecution, ObftBlockExecutionImpl}
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.consensus.validators.{
  CompositePostExecutionValidator,
  PostExecutionValidator,
  PostExecutionValidatorImpl
}
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.db.storage.{GetByNumberServiceImpl, ObftStorageBuilder}
import io.iohk.scevm.domain.UInt256.uint256ToBigInt
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.exec.vm.{EVM, StorageType, TransactionSimulator, WorldType}
import io.iohk.scevm.ledger.blockgeneration.BlockGeneratorImpl
import io.iohk.scevm.ledger.{
  BlockRewardCalculator,
  BlockRewarder,
  FixedRewardAddressProvider,
  TransactionExecutionImpl,
  _
}
import io.iohk.scevm.metrics.instruments.Counter
import io.iohk.scevm.solidity.{SolidityAbi, SolidityAbiDecoder}
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalatest.Assertions.fail
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import scala.concurrent.duration.DurationInt
import scala.io.Source

import BranchExecutionScenarioSpec._

class BranchExecutionScenarioSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IOSupport
    with NormalPatience
    with EitherValues {

  type TestMonad[T] = IO[T]

  "BranchExecution" should {

    "pass scenario" in new Fixture() {
      (for {

        // This scenario generate a few blocks with transactions calling a contract
        // The contract logs some value that depends on the context (current block number, timestamp, previous block hash)
        // Any inconsistency should be caught via the log bloom filter which will be different if the result is different
        (_, address)       <- deployContract("BlockContextContract.bin")
        _                  <- generateBlock()
        _                  <- addContractCallTransaction(address, SolidityAbi.solidityCall("checkBlockNumber"))
        _                  <- generateBlock()
        _                  <- addContractCallTransaction(address, SolidityAbi.solidityCall("checkBlockTimestamp"))
        blockToLog         <- generateBlock()
        _                  <- addContractCallTransaction(address, SolidityAbi.solidityCall("checkBlockHash"))
        blockWithHashEvent <- generateBlock()
        branch             <- getBranch()
        _                   = branch.tip.number shouldBe 4
        result             <- Step.liftF(branchExecution.execute(branch))
        allReceipts <-
          Step.liftF(
            branch.unexecuted
              .flatTraverse(b => storage.receiptStorage.getReceiptsByHash(b.hash).map(_.toVector.flatten))
          )
        receipts <- Step.liftF(storage.receiptStorage.getReceiptsByHash(blockWithHashEvent.hash))
      } yield {
        result shouldBe a[Right[_, _]]
        allReceipts.foreach(_.postTransactionStateHash shouldBe TransactionOutcome.SuccessOutcome)
        receipts match {
          case Some(Seq(receipt)) => receipt.logs.head.data shouldBe blockToLog.hash.byteString
          case Some(_)            => fail("More than one receipt was in the block")
          case None               => fail(show"No receipts for block $blockWithHashEvent")
        }
      }).run(initialState).ioValue
    }

    val genesisContractAddress = Address("0x1110000000000000000000000000000000000000")

    // This scenario uses a custom validation that check that calling a contract during the validation
    // gives the expected result This ensures that the block context in the validation is correct.
    "pass scenario with custom validation" in new Fixture(
      genesisContracts = Map(genesisContractAddress -> loadContractPayload("GetNumberContract.bin-runtime")),
      extraValidators = blockchainConfig => Seq(buildPostExecValidator(blockchainConfig, genesisContractAddress))
    ) {

      (for {

        (_, address) <- deployContract("BlockContextContract.bin")
        _            <- generateBlock()
        _            <- addContractCallTransaction(address, SolidityAbi.solidityCall("checkBlockNumber"))
        _            <- generateBlock()
        _            <- addContractCallTransaction(address, SolidityAbi.solidityCall("checkBlockTimestamp"))
        _            <- generateBlock()
        branch       <- getBranch()
        _             = branch.tip.number shouldBe 3
        result       <- Step.liftF(branchExecution.execute(branch))
      } yield result shouldBe a[Right[_, _]]).run(initialState).ioValue
    }

  }
}

object BranchExecutionScenarioSpec {

  class Fixture(
      genesisContracts: Map[Address, ByteString] = Map.empty,
      extraValidators: BlockchainConfig => Seq[PostExecutionValidator[IO]] = _ => Seq.empty
  ) {

    private val genesisData: ObftGenesisData = ObftGenesisData(
      Address("0x0000000000000000000000000000000000000000"),
      9048576,
      UnixTimestamp.fromMillis(0),
      genesisContracts.view.mapValues(code => ObftGenesisAccount(0, Some(code), None, Map.empty)).toMap,
      TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce
    )
    private val blockchainConfig: StandaloneBlockchainConfig =
      TestCoreConfigs.blockchainConfig.copy(genesisData = genesisData)

    implicit val loggerFactory: LoggerFactory[IO] = NoOpFactory[IO]

    val storage: ObftStorageBuilder[IO] =
      ObftStorageBuilder[IO](EphemDataSource(), new NoOpStorageMetrics[IO]()).unsafeRunSync()

    private val vm: EVM[WorldType, StorageType] = EVM[WorldType, StorageType]()
    private val rewardAddressProvider           = new FixedRewardAddressProvider(Address(0))
    private val transactionExecution =
      new TransactionExecutionImpl[IO](vm, SignedTransactionValidatorImpl, rewardAddressProvider, blockchainConfig)
    private val worldStateBuilder = new WorldStateBuilderImpl[IO](
      storage.evmCodeStorage,
      new GetByNumberServiceImpl(
        storage.blockchainStorage,
        storage.stableNumberMappingStorage
      ),
      storage.stateStorage,
      blockchainConfig
    )
    private val blockRewardCalculator: BlockRewardCalculator =
      BlockRewardCalculator(blockchainConfig.monetaryPolicyConfig.blockReward, rewardAddressProvider)

    private val postExecutionValidator = new CompositePostExecutionValidator(
      List(new PostExecutionValidatorImpl[IO]) ++ extraValidators(blockchainConfig)
    )
    private val blockRewarder =
      BlockRewarder.payBlockReward[IO](blockchainConfig, blockRewardCalculator) _
    private val blockPreExecution: BlockPreExecution[IO] = BlockPreExecution.noop[IO]
    private val blockExecution = new ObftBlockExecutionImpl(
      blockPreExecution,
      transactionExecution,
      postExecutionValidator,
      blockRewarder,
      Counter.noop[IO]
    )
    private val blockPreparation = new BlockPreparationImpl(
      blockPreExecution,
      transactionExecution,
      blockRewarder,
      worldStateBuilder
    )
    val blockGenerator = new BlockGeneratorImpl[IO](blockPreparation)

    val branchExecution = new BranchExecutionImpl(
      blockExecution,
      storage.blockchainStorage,
      storage.blockchainStorage,
      storage.receiptStorage,
      worldStateBuilder
    )

    val genesis: ObftBlock =
      ObftGenesisLoader
        .load[IO](storage.evmCodeStorage, storage.stateStorage)(blockchainConfig.genesisData)
        .unsafeRunSync()

    val initialState: ScenarioState = ScenarioState(Nonce(0), Nil, Seq(genesis))

    def generateBlock(): Step[ObftBlock] =
      Step(s =>
        for {
          block <- blockGenerator
                     .generateBlock(
                       s.blocks.last.header,
                       s.transactions.map(SignedTransaction.sign(_, key1, Some(blockchainConfig.chainId))),
                       s.blocks.last.header.slotNumber.add(1),
                       timestamp = s.blocks.last.header.unixTimestamp.add(5.seconds),
                       validatorPubKey = ECDSA.PublicKey.fromPrivateKey(keyValidator),
                       validatorPrvKey = keyValidator
                     )
          _ = assert(
                block.body.transactionList.length == s.transactions.length,
                "Not all transaction that were built made it into the block"
              )
          _ <- storage.blockchainStorage.insertBlock(block)
        } yield (s.copy(transactions = Nil, blocks = s.blocks :+ block), block)
      )

    def getBranch(): Step[BlocksBranch] =
      Step.inspect(s => BlocksBranch(s.blocks.head, s.blocks.drop(1).dropRight(1).toVector, s.blocks.last))

  }

  val key1: ECDSA.PrivateKey =
    ECDSA.PrivateKey.fromHexUnsafe("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f")
  val keyValidator: ECDSA.PrivateKey =
    ECDSA.PrivateKey.fromHexUnsafe("ed341f91661a05c249c36b8c9f6d3b796aa9f629f07ddc73b04b9ffc98641a50")

  final case class ScenarioState(
      nonce: Nonce,
      transactions: Seq[Transaction],
      blocks: Seq[ObftBlock]
  )
  type Step[T] = StateT[IO, ScenarioState, T]
  val Step = StateT

  def loadContractPayload(name: String): ByteString = {
    val src = Source.fromResource(name)
    val raw =
      try src.mkString
      finally src.close()
    ByteString(raw.trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  }

  def deployContract(file: String): Step[(Transaction, Address)] =
    Step { s =>
      val transaction = LegacyTransaction(
        s.nonce,
        gasPrice = 0,
        gasLimit = s.blocks.last.header.gasLimit,
        receivingAddress = None,
        value = 0,
        payload = loadContractPayload(file)
      )
      IO.pure(
        (
          s.copy(nonce = s.nonce.increaseOne, transactions = s.transactions :+ transaction),
          (
            transaction,
            ContractAddress.fromSender(Address.fromPrivateKey(key1), s.nonce)
          )
        )
      )
    }

  def addContractCallTransaction(recipient: Address, payload: ByteString): Step[LegacyTransaction] =
    Step { s =>
      val transaction = LegacyTransaction(
        s.nonce,
        gasPrice = 0,
        gasLimit = s.blocks.last.header.gasLimit,
        receivingAddress = recipient,
        value = 0,
        payload = payload
      )
      IO.pure(
        (
          s.copy(nonce = s.nonce.increaseOne, transactions = s.transactions :+ transaction),
          transaction
        )
      )
    }

  def buildPostExecValidator(blockchainConfig: BlockchainConfig, contractAddress: Address): PostExecutionValidator[IO] =
    new PostExecutionValidator[IO] {

      private val transactionSimulator =
        TransactionSimulator(blockchainConfig, EVM[WorldType, StorageType](), w => IO.pure(w))

      override def validate(
          initialState: WorldType,
          header: ObftHeader,
          result: ObftBlockExecution.BlockExecutionResult
      ): IO[Either[PostExecutionError, Unit]] = {
        val tx = LegacyTransaction(
          Nonce(0),
          gasPrice = 0,
          gasLimit = Int.MaxValue,
          receivingAddress = Some(contractAddress),
          value = 0,
          payload = SolidityAbi.solidityCall("getBlockNumber")
        )

        for {
          result <- transactionSimulator.executeTx(tx).run(initialState)
          data    = result.toEither.getOrElse(fail("contract call should have succeeded"))
          parsed  = SolidityAbiDecoder[Tuple1[UInt256]].decode(data)._1
        } yield
          if (uint256ToBigInt(parsed) == header.number.value) {
            Right(())
          } else {
            Left(
              PostExecutionError(
                header.hash,
                s"The contract returned the block number $parsed instead of ${header.number}"
              )
            )
          }

      }
    }

}
