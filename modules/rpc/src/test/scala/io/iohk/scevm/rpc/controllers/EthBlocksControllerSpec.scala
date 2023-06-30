package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.dataSource.{DataSource, EphemDataSource}
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock}
import io.iohk.scevm.ledger.{BlockProvider, BlockProviderImpl, BlockProviderStub}
import io.iohk.scevm.rpc.controllers.EthBlocksController._
import io.iohk.scevm.rpc.dispatchers.EmptyRequest
import io.iohk.scevm.testing.BlockGenerators.{obftBlockGen, obftEmptyBodyBlockGen}
import io.iohk.scevm.testing.fixtures.GenesisBlock
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.annotation.unused

class EthBlocksControllerSpec
    extends AsyncWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with NormalPatience
    with BlocksFixture {

  private val chainId: ChainId = TestCoreConfigs.chainId

  private val existingBlock: ObftBlock    = Block3125369.block
  private val nonExistingBlock: ObftBlock = Block123.block

  private val datasource: DataSource = EphemDataSource()
  private val blockchainStorage      = BlockchainStorage.unsafeCreate[IO](datasource)
  private val stableNumberMappingStorage: StableNumberMappingStorage[IO] =
    new StableNumberMappingStorageImpl(datasource)
  private val getByNumberService: GetByNumberService[IO] =
    new GetByNumberServiceImpl(blockchainStorage, stableNumberMappingStorage)
  @unused implicit private val currentBranch: CurrentBranch.Signal[IO] =
    Signal.constant(CurrentBranch(existingBlock.header))
  private val blockProvider: BlockProvider[IO] = new BlockProviderImpl(blockchainStorage, getByNumberService)
  private val blockResolver: BlockResolver[IO] = new BlockResolverImpl(blockProvider)
  private val ethBlocksController              = new EthBlocksController(blockResolver, blockProvider, chainId)

  blockchainStorage.insertBlock(Block3125369.block).ioValue

  "EthBlocksController" when {
    "eth_getBlockByHash is called" should {
      "return a correct response if the block exists (full transactions)" in {
        val response = ethBlocksController
          .getBlockByHash(GetBlockByHashRequest(existingBlock.hash, fullTxs = true))
          .ioValue
          .value
        response.blockResponse shouldBe Some(expectedFullRpcBlockResponseForBlock3125369)
      }

      "return a correct response if the block exists (no full transactions)" in {
        val response = ethBlocksController
          .getBlockByHash(GetBlockByHashRequest(existingBlock.hash, fullTxs = false))
          .ioValue
          .value
        response.blockResponse shouldBe Some(expectedNotFullRpcBlockResponseForBlock3125369)
      }

      "return None if the block doesn't exist" in {
        val response = ethBlocksController
          .getBlockByHash(GetBlockByHashRequest(nonExistingBlock.hash, fullTxs = true))
          .ioValue
          .value
        response.blockResponse shouldBe None
      }
    }

    "eth_blockNumber" should {
      "answer with the latest block number" in {
        val validBlock    = obftBlockGen.sample.get
        val blockProvider = BlockProviderStub[IO](List.empty, validBlock, validBlock)
        val blockResolver = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(blockResolver, blockProvider, chainId)

        ethBlocksController
          .bestBlockNumber(EmptyRequest())
          .map(_.toOption.get)
          .map { response =>
            response shouldEqual validBlock.number
          }
          .unsafeToFuture()
      }

      "return number 0 if there are no blocks after genesis" in {
        val blockProvider      = BlockProviderStub[IO](List.empty, GenesisBlock.block, GenesisBlock.block)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        ethBlocksController
          .bestBlockNumber(EmptyRequest())
          .map(_.toOption.get)
          .map { response =>
            response shouldEqual 0
          }
          .unsafeToFuture()
      }
    }

    "eth_getBlockTransactionCountByHash" should {
      "return None when the requested block isn't in the blockchain" in {
        val validBlock         = obftBlockGen.sample.get
        val blockProvider      = BlockProviderStub[IO](List(validBlock), validBlock, validBlock)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        val blockHash =
          BlockHash(ByteString.fromString("0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))
        ethBlocksController
          .getBlockTransactionCountByHash(blockHash)
          .map(_.toOption.get)
          .map(result => result shouldBe None)
          .unsafeToFuture()

      }

      "return number of tx when Block is in the Blockchain and has transactions" in {
        val validBlock         = obftBlockGen.sample.get
        val blockProvider      = BlockProviderStub[IO](List(validBlock), validBlock, validBlock)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        val blockHash           = BlockHash(validBlock.hash.byteString)
        ethBlocksController
          .getBlockTransactionCountByHash(blockHash)
          .map(_.toOption.get)
          .map(result => result shouldBe Some(10))
          .unsafeToFuture()
      }

      "return no tx when Block is in the Blockchain and has no transactions" in {
        val validBlock         = obftEmptyBodyBlockGen.sample.get
        val blockProvider      = BlockProviderStub[IO](List(validBlock), validBlock, validBlock)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        val blockHash           = BlockHash(validBlock.hash.byteString)
        ethBlocksController
          .getBlockTransactionCountByHash(blockHash)
          .map(_.toOption.get)
          .map(result => result shouldBe Some(validBlock.body.transactionList.length))
          .unsafeToFuture()
      }
    }

    "eth_getBlockTransactionCountByNumber" should {
      "return zero when the requested block isn't in the blockchain" in {
        val validBlock         = obftBlockGen.sample.get
        val blockProvider      = BlockProviderStub[IO](List(validBlock), validBlock, validBlock)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        val blockNumber         = BlockNumber(343434)

        ethBlocksController
          .getBlockTransactionCountByNumber(BlockParam.ByNumber(blockNumber))
          .map(_.toOption.get)
          .map(result => result shouldBe 0)
          .unsafeToFuture()
      }

      "return number of tx when Block is in the Blockchain and has transactions" in {
        val validBlock         = obftBlockGen.sample.get
        val blockProvider      = BlockProviderStub[IO](List(validBlock), validBlock, validBlock)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        ethBlocksController
          .getBlockTransactionCountByNumber(BlockParam.ByNumber(validBlock.header.number))
          .map(_.toOption.get)
          .map(result => result shouldBe validBlock.body.transactionList.length)
          .unsafeToFuture()
      }

      "return zero when Block is in the Blockchain and has no transactions" in {
        val validBlock         = obftEmptyBodyBlockGen.sample.get
        val blockProvider      = BlockProviderStub[IO](List(validBlock), validBlock, validBlock)
        val mockedResolveBlock = new BlockResolverImpl(blockProvider)

        val ethBlocksController = new EthBlocksController(mockedResolveBlock, blockProvider, chainId)
        ethBlocksController
          .getBlockTransactionCountByNumber(BlockParam.ByNumber(validBlock.header.number))
          .map(_.toOption.get)
          .map(result => result shouldBe validBlock.body.transactionList.length)
          .unsafeToFuture()
      }
    }
  }
}
