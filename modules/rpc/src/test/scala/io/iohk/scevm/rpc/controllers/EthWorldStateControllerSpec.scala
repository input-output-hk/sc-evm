package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.consensus.{WorldStateBuilder, WorldStateBuilderImpl}
import io.iohk.scevm.db.dataSource.{DataSource, EphemDataSource}
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{Account, Address, Nonce, ObftBlock, UInt256}
import io.iohk.scevm.ledger.{BlockProvider, BlockProviderImpl}
import io.iohk.scevm.mpt.{EthereumUInt256Mpt, MerklePatriciaTrie}
import io.iohk.scevm.rpc.controllers.EthWorldStateController._
import io.iohk.scevm.rpc.{AccountService, AccountServiceImpl}
import io.iohk.scevm.serialization.Implicits.byteArraySerializable
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAsyncWordSpec

class EthWorldStateControllerSpec
    extends FixtureAsyncWordSpec
    with Matchers
    with AsyncDataSourceFixture
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "EthWorldStateController" when {
    "getBalance is called" should {
      "return balance 0 when address can't be found" in { fixture =>
        val newAddress = Address(Hex.decodeUnsafe("abbb6bebfa05aa13e908eaa492bd7a8343760488"))

        val ethWorldStateController = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        ethWorldStateController
          .getBalance(GetBalanceRequest(newAddress, BlockParam.Latest))
          .map(_.toOption.get)
          .map(_ shouldBe BigInt(0))
          .unsafeToFuture()
      }

      "return balance when address is found" in { fixture =>
        val ethWorldStateController = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        ethWorldStateController
          .getBalance(GetBalanceRequest(fixture.address, BlockParam.Latest))
          .map(_.toOption.get)
          .map(_ shouldBe BigInt(10))
          .unsafeToFuture()
      }
    }

    "getStorageAt is called" should {
      "return stored value at valid position" in { fixture =>
        val ethWorldStateService = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        val position = 333

        ethWorldStateService
          .getStorageAt(GetStorageAtRequest(fixture.address, position, BlockParam.Latest))
          .map(_.toOption.get)
          .map(result => UInt256(result.value) shouldEqual UInt256(123))
          .unsafeToFuture()
      }

      "return 0 when position has no value" in { fixture =>
        val ethWorldStateService = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        val position = 334

        ethWorldStateService
          .getStorageAt(GetStorageAtRequest(fixture.address, position, BlockParam.Latest))
          .map(_.toOption.get)
          .map(result => UInt256(result.value) shouldEqual UInt256(0))
          .unsafeToFuture()
      }
    }

    "getTransactionCount is called" should {
      "return transaction count 0 when address can't be found" in { fixture =>
        val newAddress = Address(Hex.decodeUnsafe("abbb6bebfa05aa13e908eaa492bd7a8343760488"))

        val ethWorldStateController = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        ethWorldStateController
          .getTransactionCount(GetTransactionCountRequest(newAddress, BlockParam.Latest))
          .map(_.toOption.get)
          .map(_ shouldBe Nonce.Zero)
          .unsafeToFuture()
      }

      "return transaction count when address can be found" in { fixture =>
        val ethWorldStateController = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        ethWorldStateController
          .getTransactionCount(GetTransactionCountRequest(fixture.address, BlockParam.Latest))
          .map(_.toOption.get)
          .map(_ shouldBe Nonce(999))
          .unsafeToFuture()
      }
    }

    "getCode is called" should {
      "return the code at a given address when found" in { fixture =>
        val ethWorldStateController = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        ethWorldStateController
          .getCode(GetCodeRequest(fixture.address, BlockParam.Latest))
          .map(_.toOption.get)
          .map(_ shouldBe Some(ByteString("code code code")))
          .unsafeToFuture()
      }

      "return Empty when given address is when found" in { fixture =>
        val newAddress = Address(Hex.decodeUnsafe("abbb6bebfa05aa13e908eaa492bd7a8343760488"))
        val ethWorldStateController = new EthWorldStateController(
          fixture.resolveBlock,
          fixture.stateStorage,
          fixture.blockchainConfig,
          fixture.accountProvider,
          fixture.worldStateBuilder
        )

        ethWorldStateController
          .getCode(GetCodeRequest(newAddress, BlockParam.Latest))
          .map(_.toOption.get)
          .map(_ shouldBe Some(ByteString("")))
          .unsafeToFuture()
      }
    }
  }

  case class FixtureParam(
      blockProvider: BlockProvider[IO],
      resolveBlock: BlockResolver[IO],
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorageImpl,
      blockchainConfig: BlockchainConfig,
      address: Address,
      accountProvider: AccountService[IO],
      worldStateBuilder: WorldStateBuilder[IO]
  )

  // scalastyle:off
  override def initFixture(dataSource: DataSource): FixtureParam = {
    val storageMetrics    = NoOpStorageMetrics[IO]()
    val blockchainStorage = BlockchainStorage.unsafeCreate[IO](dataSource)
    val stateStorage = StateStorage(
      new NodeStorage(dataSource, storageMetrics.readTimeForNode, storageMetrics.writeTimeForNode)
    )
    val stableNumberMappingStorage = new StableNumberMappingStorageImpl[IO](dataSource)

    val stableBlock: ObftBlock = obftBlockGen.sample.get

    val address = Address(Hex.decodeUnsafe("abbb6bebfa05aa13e908eaa492bd7a8343760477"))

    val accountStorageContentsTrie = EthereumUInt256Mpt
      .storageMpt(
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        stateStorage.archivingStorage
      )
      .put(UInt256(333), UInt256(123))

    val worldStateTrie = MerklePatriciaTrie[Array[Byte], Account](
      stateStorage.archivingStorage
    )
      .put(
        crypto.kec256(address.bytes.toArray[Byte]),
        Account(Nonce(999), UInt256(10), ByteString(accountStorageContentsTrie.getRootHash), ByteString("code hash"))
      )

    val blockHeader = stableBlock.header.copy(stateRoot = ByteString(worldStateTrie.getRootHash))
    val blockToSave = stableBlock.copy(header = blockHeader)
    blockchainStorage.insertBlock(blockToSave).unsafeRunSync()

    implicit val currentBranch: Signal[IO, CurrentBranch] = Signal.constant(CurrentBranch(blockToSave.header))

    val getByNumberService: GetByNumberService[IO] = new GetByNumberServiceImpl(
      blockchainStorage,
      stableNumberMappingStorage
    )

    val evmCodeStorage =
      new EvmCodeStorageImpl(EphemDataSource(), storageMetrics.readTimeForEvmCode, storageMetrics.writeTimeForEvmCode)
    evmCodeStorage.put(ByteString("code hash"), ByteString("code code code"))

    val blockchainConfig = TestCoreConfigs.blockchainConfig

    val blockProvider = new BlockProviderImpl[IO](blockchainStorage, getByNumberService)
    val resolveBlock  = new BlockResolverImpl[IO](blockProvider)
    val worldBuilder  = new WorldStateBuilderImpl[IO](evmCodeStorage, getByNumberService, stateStorage, blockchainConfig)
    val accountProvider = new AccountServiceImpl(
      worldBuilder,
      blockchainConfig
    )
    FixtureParam(
      blockProvider,
      resolveBlock,
      stateStorage,
      evmCodeStorage,
      blockchainConfig,
      address,
      accountProvider,
      worldBuilder
    )
  }
  // scalastyle:on
}
