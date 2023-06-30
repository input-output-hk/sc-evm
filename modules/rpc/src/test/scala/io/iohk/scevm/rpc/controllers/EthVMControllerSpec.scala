package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{Address, Token}
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.ledger.BlockProviderStub
import io.iohk.scevm.mpt._
import io.iohk.scevm.rpc.controllers.EthVMController.CallTransaction
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.fixtures.GenesisBlock
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class EthVMControllerSpec extends AsyncWordSpec with Matchers with ScalaFutures with IOSupport with NormalPatience {

  val blockchainConfig: BlockchainConfig = TestCoreConfigs.blockchainConfig

  lazy val genesis    = GenesisBlock.block
  lazy val mptStorage = new EphemeralMptStorage()

  val dataSource: EphemDataSource      = EphemDataSource()
  val nodeStorage                      = new NodeStorage(dataSource)
  val mockedStateStorage: StateStorage = new ArchiveStateStorage(nodeStorage)

  implicit val current: CurrentBranch.Signal[IO] = Signal.constant(CurrentBranch(genesis.header, genesis.header))

  val blockProvider: BlockProviderStub[IO] = BlockProviderStub[IO](List(genesis), genesis, genesis)
  val mockedResolveBlock                   = new BlockResolverImpl(blockProvider)
  val blockchainStorage: BlocksWriter[IO] with BlocksReader[IO] with BranchProvider[IO] =
    BlockchainStorage.unsafeCreate[IO](dataSource)
  val storageMetrics: NoOpStorageMetrics[IO] = NoOpStorageMetrics[IO]()
  val codeStorage =
    new EvmCodeStorageImpl(dataSource, storageMetrics.readTimeForEvmCode, storageMetrics.writeTimeForEvmCode)

  val evm: VM[IO, WorldType, StorageType] = EVM[WorldType, StorageType]()
  val ethVMController =
    new EthVMController(
      blockchainConfig,
      mockedResolveBlock,
      TransactionSimulator[IO](
        TestCoreConfigs.blockchainConfig,
        evm,
        s => IO.pure(s)
      ),
      new WorldStateBuilderImpl[IO](
        codeStorage,
        new GetByNumberServiceImpl[IO](blockchainStorage, new StableNumberMappingStorageImpl[IO](dataSource)),
        mockedStateStorage,
        blockchainConfig
      )
    )

  "eth_estimateGas" should {
    "give back the gas consumption of calling the sha256 contract" in {

      val result = ethVMController.estimateGas(
        EthVMController.EstimateGasRequest(
          CallTransaction(
            to = Some(Address("0x0000000000000000000000000000000000000002")),
            input = ByteString.empty,
            from = None,
            gas = None,
            gasPrice = None,
            value = Token(0)
          ),
          Option(BlockParam.Latest)
        )
      )

      result.ioValue.map(_.value) shouldBe Right(BigInt(22744))
    }

  }

  "eth_call" should {
    "give back the sha256 of empty string when calling a predefined contract" in {

      val result = ethVMController.call(
        EthVMController.CallRequest(
          CallTransaction(
            to = Some(Address("0x0000000000000000000000000000000000000002")),
            input = ByteString.empty,
            from = None,
            gas = None,
            gasPrice = None,
            value = Token(0)
          ),
          BlockParam.Latest
        )
      )

      result.ioValue.map(_.returnData) shouldBe Right(
        ByteString(Hex.decodeAsArrayUnsafe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
      )

    }
  }

}
