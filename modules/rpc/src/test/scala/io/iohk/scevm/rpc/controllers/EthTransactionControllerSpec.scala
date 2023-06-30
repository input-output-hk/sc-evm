package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import com.softwaremill.quicklens._
import fs2.concurrent.SignallingRef
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.SignedTransaction.byteArraySerializable.toBytes
import io.iohk.scevm.domain.TransactionOutcome.HashOutcome
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.ledger.{BlockProviderImpl, BloomFilter}
import io.iohk.scevm.rpc.controllers.EthTransactionController._
import io.iohk.scevm.rpc.controllers.PersonalControllerFixture.nonce
import io.iohk.scevm.rpc.domain.{JsonRpcError, RpcFullTransactionResponse}
import io.iohk.scevm.rpc.router.AccountServiceStub
import io.iohk.scevm.rpc.{FilterConfig, TestRpcConfig}
import io.iohk.scevm.testing.TestCoreConfigs
import io.iohk.scevm.testing.fixtures.{GenesisBlock, ValidBlock}
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class EthTransactionControllerSpec
    extends AsyncWordSpec
    with Matchers
    with BlocksFixture
    with AsyncMockFactory
    with EitherValues {

  import cats.effect.unsafe.implicits.global

  "EthTransactionController" when {
    "gasPrice() is called" should {
      "return configured value" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionController

        ethTransactionController
          .gasPrice()
          .map(_.toOption.get)
          .map(response => response shouldBe BigInt(0))
          .unsafeToFuture()
      }
    }

    "sendRawTransaction() is called" should {
      "accept transaction if sender can be derived" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionController

        val expectedHash = TransactionHash(
          Hex.decodeUnsafe("8640165a7ca37a10d568b13509cfeed84b764640d523cbd0edbdd981fd2725d7")
        )

        (for {
          result  <- ethTransactionController.sendRawTransaction(fixture.signedTxRequest)
          response = result.toOption.get
        } yield response shouldBe expectedHash).unsafeToFuture()
      }

      "return InvalidRequest if sender cannot be derived" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionController

        ethTransactionController
          .sendRawTransaction(fixture.legacyTxRequest)
          .map {
            case Left(error) => error shouldBe JsonRpcError.InvalidParams("Error deriving Transaction sender")
            case Right(_)    => fail("JsonRpcError.InvalidParams expected")
          }
          .unsafeToFuture()
      }

      "return TransactionSenderCantPayUpfrontCostError if account does not have enough balance" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionControllerNotEnoughAccountBalance

        ethTransactionController
          .sendRawTransaction(fixture.signedTxRequest)
          .map {
            case Left(error) =>
              error shouldBe JsonRpcError.InvalidParams(
                "Insufficient balance: transaction's Upfrontcost (25010) > sender balance (10)."
              )
            case Right(_) => fail("JsonRpcError.InvalidParams expected")
          }
          .unsafeToFuture()
      }
    }

    "getTransactionReceipt is called" should {
      "calculate contract address and return receipt" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] =
            Seq(fakeReceipt, fakeReceiptWithGasUsed)
        }

        val ethTransactionController = fixture.ethTransactionController

        ethTransactionController
          .getTransactionReceipt(fixture.contractCreatingTransaction.hash)
          .map(_.toOption.get)
          .map { response =>
            response should not be empty
            val expectedReceipt = fixture.expectedGetTransactionReceiptResponse.get
            val responseReceipt = response.get
            responseReceipt.transactionHash shouldBe expectedReceipt.transactionHash
            responseReceipt.transactionIndex shouldBe expectedReceipt.transactionIndex
            responseReceipt.blockNumber shouldBe expectedReceipt.blockNumber
            responseReceipt.blockHash shouldBe expectedReceipt.blockHash
            responseReceipt.from shouldBe expectedReceipt.from
            responseReceipt.to shouldBe expectedReceipt.to
            responseReceipt.cumulativeGasUsed shouldBe expectedReceipt.cumulativeGasUsed
            responseReceipt.gasUsed shouldBe expectedReceipt.gasUsed
            responseReceipt.contractAddress shouldBe expectedReceipt.contractAddress
            responseReceipt.logs shouldBe expectedReceipt.logs
            responseReceipt.logsBloom shouldBe expectedReceipt.logsBloom
            responseReceipt.root shouldBe expectedReceipt.root
            responseReceipt.status shouldBe expectedReceipt.status
          }
          .unsafeToFuture()
      }

      "not calculate contract when receipts are missing" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionController
        ethTransactionController
          .getTransactionReceipt(fixture.contractCreatingTransaction.hash)
          .map(_.toOption.get)
          .map(response => response shouldBe empty)
          .unsafeToFuture()
      }
    }

    "getTransactionByHash is called" should {
      "return the transaction when found" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionController

        ethTransactionController
          .getTransactionByHash(fixture.contractCreatingTransaction.hash)
          .map(_.toOption.get)
          .map { response =>
            response should not be empty
            val expectedTransaction = fixture.expectedGetTransactionByHashResponse.get
            val responseTransaction = response.get
            responseTransaction shouldBe expectedTransaction
          }
          .unsafeToFuture()
      }

      "return none when transaction isn't found" in {
        object fixture extends InitFixture
        val ethTransactionController = fixture.ethTransactionController
        ethTransactionController
          .getTransactionByHash(TransactionHash(Hex.decodeUnsafe("0")))
          .map(_.toOption.get)
          .map(response => response shouldBe empty)
          .unsafeToFuture()
      }
    }

    "eth_getLogs is called" should {
      "return every logs" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] =
            Seq(fakeReceipt, fakeReceiptWithGasUsed)
        }

        val ethTransactionController = fixture.ethTransactionController

        val request =
          GetLogsRequestByRange(BlockParam.Earliest, BlockParam.Latest, Seq.empty[Address], Seq.empty[TopicSearch])

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response =>
            response shouldBe Seq(
              fixture.expectedFirstTransactionLogWithRemoved,
              fixture.expectedSecondTransactionLogWithRemoved
            )
          )
          .unsafeToFuture()
      }

      "return every logs (fromBlock = toBlock = Latest)" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] =
            Seq(fakeReceipt, fakeReceiptWithGasUsed)
        }

        val ethTransactionController = fixture.ethTransactionController

        val request =
          GetLogsRequestByRange(BlockParam.Latest, BlockParam.Latest, Seq.empty[Address], Seq.empty[TopicSearch])

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response =>
            response shouldBe Seq(
              fixture.expectedFirstTransactionLogWithRemoved,
              fixture.expectedSecondTransactionLogWithRemoved
            )
          )
          .unsafeToFuture()
      }

      "return every logs associated to a specific block" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] =
            Seq(fakeReceipt, fakeReceiptWithGasUsed)
        }

        val ethTransactionController = fixture.ethTransactionController

        val request = GetLogsRequestByRange(
          BlockParam.ByNumber(BlockNumber(1)),
          BlockParam.ByNumber(BlockNumber(1)),
          Seq.empty[Address],
          Seq.empty[TopicSearch]
        )

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response =>
            response shouldBe Seq(
              fixture.expectedFirstTransactionLogWithRemoved,
              fixture.expectedSecondTransactionLogWithRemoved
            )
          )
          .unsafeToFuture()
      }

      "fail if fromBlock > toBlock" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] =
            Seq(fakeReceipt, fakeReceiptWithGasUsed)
        }

        val ethTransactionController = fixture.ethTransactionController

        val request =
          GetLogsRequestByRange(BlockParam.Latest, BlockParam.Earliest, Seq.empty[Address], Seq.empty[TopicSearch])

        ethTransactionController
          .getLogs(request)
          .map(response => response shouldBe Left(JsonRpcError.BlockNotFound))
          .unsafeToFuture()
      }

      "return an empty array if the requested block doesn't contain transactions" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] =
            Seq(fakeReceipt, fakeReceiptWithGasUsed)
        }
        val ethTransactionController = fixture.ethTransactionController

        val request = GetLogsRequestByRange(
          BlockParam.ByNumber(BlockNumber(0)),
          BlockParam.ByNumber(BlockNumber(0)),
          Seq.empty[Address],
          Seq.empty[TopicSearch]
        )

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response => response shouldBe Seq.empty[TransactionLogWithRemoved])
          .unsafeToFuture()
      }

      "return every logs associated to a specific Address" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(
            // Update address to 43
            fakeReceipt.modify(_.receiptBody.logs.each.loggerAddress).setTo(Address(43)),
            fakeReceiptWithGasUsed
          )
        }

        val ethTransactionController = fixture.ethTransactionController

        val request =
          GetLogsRequestByRange(BlockParam.Earliest, BlockParam.Latest, Seq(Address(42)), Seq.empty[TopicSearch])

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response => response shouldBe Seq(fixture.expectedSecondTransactionLogWithRemoved))
          .unsafeToFuture()
      }

      "return every logs having an Address contained in a given list of Address" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(
            // Update address to 43
            fakeReceipt.modify(_.receiptBody.logs.each.loggerAddress).setTo(Address(43)),
            fakeReceiptWithGasUsed
          )
        }

        val ethTransactionController = fixture.ethTransactionController

        val request = GetLogsRequestByRange(
          BlockParam.Earliest,
          BlockParam.Latest,
          Seq(Address(42), Address(43)),
          Seq.empty[TopicSearch]
        )

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response =>
            response shouldBe Seq(
              fixture.expectedFirstTransactionLogWithRemoved.copy(address = Address(43)),
              fixture.expectedSecondTransactionLogWithRemoved
            )
          )
          .unsafeToFuture()
      }

      "return every logs having a given topic in first position" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(
            // Update topics to 00 * 32 instead of 01 * 32
            fakeReceipt.modify(_.receiptBody.logs.each.logTopics).setTo(Seq(Hex.decodeUnsafe("00" * 32))),
            fakeReceiptWithGasUsed
          )
        }

        val ethTransactionController = fixture.ethTransactionController

        val request = GetLogsRequestByRange(
          BlockParam.Earliest,
          BlockParam.Latest,
          Seq.empty[Address],
          Seq(ExactTopicSearch(Hex.decodeUnsafe("01" * 32)))
        )

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response => response shouldBe Seq(fixture.expectedSecondTransactionLogWithRemoved))
          .unsafeToFuture()
      }

      "return every logs having any topic in first position" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(
            fakeReceipt,
            fakeReceiptWithGasUsed
          )
        }

        val ethTransactionController = fixture.ethTransactionController

        val request = GetLogsRequestByRange(
          BlockParam.Earliest,
          BlockParam.Latest,
          Seq.empty[Address],
          Seq(AnythingTopicSearch)
        )

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response =>
            response shouldBe Seq(
              fixture.expectedFirstTransactionLogWithRemoved,
              fixture.expectedSecondTransactionLogWithRemoved
            )
          )
          .unsafeToFuture()
      }

      "return every logs matching specific criteria on topics" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(
            // Update topics to 00 * 32 instead of 01 * 32
            fakeReceipt.modify(_.receiptBody.logs.each.logTopics).setTo(Seq(Hex.decodeUnsafe("00" * 32))),
            fakeReceiptWithGasUsed
          )
        }

        val ethTransactionController = fixture.ethTransactionController

        val request = GetLogsRequestByRange(
          BlockParam.Earliest,
          BlockParam.Latest,
          Seq.empty[Address],
          Seq(
            OrTopicSearch(
              Set(
                ExactTopicSearch(Hex.decodeUnsafe("01" * 32)),
                ExactTopicSearch(Hex.decodeUnsafe("03" * 32))
              )
            )
          )
        )

        ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response => response shouldBe Seq(fixture.expectedSecondTransactionLogWithRemoved))
          .unsafeToFuture()
      }

      "should not return logs for topic not matched by the log bloom filter" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(fakeReceipt)

          override lazy val obftBlock: ObftBlock =
            super.obftBlock.modify(_.header.logsBloom).setTo(ByteString(Hex.decodeUnsafe("00" * 256)))
        }

        val request = GetLogsRequestByRange(
          BlockParam.Earliest,
          BlockParam.Latest,
          Seq.empty[Address],
          Seq(
            OrTopicSearch(
              fixture.fakeReceipt.receiptBody.logs.head.logTopics.map(ExactTopicSearch).toSet
            )
          )
        )

        fixture.ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response => response shouldBe Seq())
          .unsafeToFuture()
      }

      "should not return logs for address not matched by the log bloom filter" in {
        object fixture extends InitFixture {
          override lazy val obftBlockTransactions: Seq[Receipt] = Seq(fakeReceipt)

          override lazy val obftBlock: ObftBlock =
            super.obftBlock.modify(_.header.logsBloom).setTo(ByteString(Hex.decodeUnsafe("00" * 256)))
        }

        val request = GetLogsRequestByRange(
          BlockParam.Earliest,
          BlockParam.Latest,
          Seq(fixture.fakeReceipt.receiptBody.logs.head.loggerAddress),
          Seq.empty[TopicSearch]
        )

        fixture.ethTransactionController
          .getLogs(request)
          .map(_.toOption.get)
          .map(response => response shouldBe Seq())
          .unsafeToFuture()
      }
    }
  }

  class InitFixture {
    lazy val blockchainConfig: BlockchainConfig = TestCoreConfigs.blockchainConfig

    lazy val legacyTx: LegacyTransaction = LegacyTransaction(
      nonce = Nonce(9),
      gasPrice = 20 * BigInt(10).pow(9),
      gasLimit = 21000,
      receivingAddress = Address("0x3535353535353535353535353535353535353535"),
      value = BigInt(10).pow(18),
      payload = ByteString.empty
    )
    lazy val legacySignedTx: SignedTransaction = SignedTransaction(legacyTx, ECDSASignature(1, 2, 1))

    lazy val txType01: TransactionType01 = TransactionType01(
      chainId = 78,
      nonce = Nonce(7),
      gasPrice = 1,
      gasLimit = 25000,
      receivingAddress = Address.apply("b94f5374fce5edbc8e2a8697c15331677e6ebf0b"),
      value = 10,
      payload = Hex.decodeUnsafe("5544"),
      accessList = Nil
    )

    lazy val signedTxType01: SignedTransaction = SignedTransaction(
      txType01,
      ECDSASignature
        .fromBytes(
          ByteString(
            Hex.decodeAsArrayUnsafe(
              "c9519f4f2b30335884581971573fadf60c6204f59a911df35ee8a540456b266032f1e8e2c5dd761f9e4f88f41c8310aeaba26a8bfcdacfedfa12ec3862d3752101"
            )
          )
        )
        .value
    )

    lazy val v: Byte       = 0x1c
    lazy val r: ByteString = Hex.decodeUnsafe("b3493e863e48a8d67572910933114a4c0e49dac0cb199e01df1575f35141a881")
    lazy val s: ByteString = Hex.decodeUnsafe("5ba423ae55087e013686f89ad71a449093745f7edb4eb39f30acd30a8964522d")

    lazy val payload: ByteString = ByteString(
      Hex.decodeAsArrayUnsafe(
        "60606040526040516101e43803806101e483398101604052808051820191906020018051906020019091908051" +
          "9060200190919050505b805b83835b600060018351016001600050819055503373ffffffffffffffffffffffff" +
          "ffffffffffffffff16600260005060016101008110156100025790900160005b50819055506001610102600050" +
          "60003373ffffffffffffffffffffffffffffffffffffffff168152602001908152602001600020600050819055" +
          "50600090505b82518110156101655782818151811015610002579060200190602002015173ffffffffffffffff" +
          "ffffffffffffffffffffffff166002600050826002016101008110156100025790900160005b50819055508060" +
          "0201610102600050600085848151811015610002579060200190602002015173ffffffffffffffffffffffffff" +
          "ffffffffffffff168152602001908152602001600020600050819055505b80600101905080506100b9565b8160" +
          "00600050819055505b50505080610105600050819055506101866101a3565b610107600050819055505b505b50" +
          "5050602f806101b56000396000f35b600062015180420490506101b2565b905636600080376020600036600073" +
          "6ab9dd83108698b9ca8d03af3c7eb91c0e54c3fc60325a03f41560015760206000f30000000000000000000000" +
          "000000000000000000000000000000000000000060000000000000000000000000000000000000000000000000" +
          "000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000" +
          "0000000000000000000000000000000000000000000000000000020000000000000000000000006c9fbd9a7f06" +
          "d62ce37db2ab1e1b0c288edc797a000000000000000000000000c482d695f42b07e0d6a22925d7e49b46fd9a3f80"
      )
    )

    lazy val contractCreatingTransaction: SignedTransaction = SignedTransaction(
      LegacyTransaction(
        nonce = Nonce(2550),
        gasPrice = BigInt("20000000000"),
        gasLimit = 3000000,
        receivingAddress = None,
        value = 0,
        payload
      ),
      v,
      r,
      s
    )

    lazy val genesisBlock: ObftBlock = GenesisBlock.block

    lazy val obftBlockTransactions: Seq[Receipt] = Nil

    def obftBlock: ObftBlock = ObftBlock(
      Block3125369.header.copy(
        number = BlockNumber(1),
        parentHash = genesisBlock.hash,
        logsBloom = BloomFilter.create(obftBlockTransactions.flatMap(_.logs))
      ),
      ObftBody(Seq(Block3125369.body.transactionList.head, contractCreatingTransaction))
    )

    lazy val gasUsedByTx = 4242
    lazy val fakeReceipt: LegacyReceipt = LegacyReceipt(
      ReceiptBody(
        postTransactionStateHash = HashOutcome(Hex.decodeUnsafe("01" * 32)),
        cumulativeGasUsed = 43,
        logsBloomFilter = Hex.decodeUnsafe("00" * 256),
        logs = Seq(
          TransactionLogEntry(
            Address(42),
            Seq(Hex.decodeUnsafe("01" * 32)),
            Hex.decodeUnsafe("03" * 32)
          )
        )
      )
    )
    lazy val fakeReceiptWithGasUsed: LegacyReceipt =
      fakeReceipt.modify(_.receiptBody.cumulativeGasUsed).using(_ + gasUsedByTx)

    lazy val validBlock: ObftBlock = ValidBlock.block

    lazy val blockchainStorage: BlocksWriter[IO] with BlocksReader[IO] with BranchProvider[IO] =
      BlockchainStorage.unsafeCreate[IO](EphemDataSource())
    lazy val stableNumberMappingStorage         = new StableNumberMappingStorageImpl[IO](EphemDataSource())
    lazy val transactionMappingStorage          = new StableTransactionMappingStorageImpl[IO](EphemDataSource())
    lazy val receiptStorage: ReceiptStorage[IO] = ReceiptStorage.unsafeCreate[IO](EphemDataSource())

    implicit lazy val currentBranch: SignallingRef[IO, CurrentBranch] =
      SignallingRef[IO].of(CurrentBranch(validBlock.header)).unsafeRunSync()

    // stable block genesis
    // unstable block 1 with transaction

    (for {
      _ <- blockchainStorage.insertBlock(genesisBlock)
      _ <- receiptStorage.putReceipts(genesisBlock.hash, Nil)
      _ <- blockchainStorage.insertBlock(validBlock)
      _ <- receiptStorage.putReceipts(validBlock.hash, Nil)
      _ <- blockchainStorage.insertBlock(obftBlock)
      _ <- receiptStorage.putReceipts(obftBlock.hash, obftBlockTransactions)
      _ <- currentBranch.set(CurrentBranch(validBlock.header, obftBlock.header))
    } yield ()).unsafeRunSync()

    lazy val contractCreatingTransactionSender: Address =
      SignedTransaction.getSender(contractCreatingTransaction)(blockchainConfig.chainId).get
    lazy val expectedGetTransactionReceiptResponse: Option[TransactionReceiptResponse] =
      Some(
        TransactionReceiptResponse(
          receipt = fakeReceiptWithGasUsed,
          signedTx = contractCreatingTransaction,
          signedTransactionSender = contractCreatingTransactionSender,
          transactionIndex = 1,
          obftHeader = obftBlock.header,
          gasUsedByTransaction = gasUsedByTx
        )
      )
    lazy val expectedGetTransactionByHashResponse: Option[RpcFullTransactionResponse] =
      Some(
        RpcFullTransactionResponse
          .from(
            contractCreatingTransaction,
            1,
            obftBlock.hash,
            obftBlock.number,
            TestCoreConfigs.blockchainConfig.chainId
          )
          .value
      )
    lazy val signedTxRequest: ByteString = ByteString(toBytes(signedTxType01))
    lazy val legacyTxRequest: ByteString = ByteString(toBytes(legacySignedTx))

    lazy val transactionBlockService =
      new GetTransactionBlockServiceImpl[IO](blockchainStorage, transactionMappingStorage)

    lazy val getByNumberService: GetByNumberService[IO] = new GetByNumberServiceImpl(
      blockchainStorage,
      stableNumberMappingStorage
    )
    lazy val blockProvider = new BlockProviderImpl[IO](blockchainStorage, getByNumberService)
    lazy val resolveBlock  = new BlockResolverImpl[IO](blockProvider)

    lazy val filterConfig: FilterConfig = TestRpcConfig.filterConfig

    lazy val ethTransactionController =
      new EthTransactionController(
        blockchainConfig.ethCompatibility,
        blockchainStorage,
        (hash: BlockHash) => receiptStorage.getReceiptsByHash(hash),
        blockchainConfig,
        transactionBlockService,
        NewTransactionListenerStub(),
        SignedTransactionValidatorImpl,
        AccountServiceStub(Account(nonce, UInt256(Long.MaxValue))),
        resolveBlock,
        blockProvider,
        filterConfig
      )

    lazy val ethTransactionControllerNotEnoughAccountBalance =
      new EthTransactionController(
        blockchainConfig.ethCompatibility,
        blockchainStorage,
        (hash: BlockHash) => receiptStorage.getReceiptsByHash(hash),
        blockchainConfig,
        transactionBlockService,
        NewTransactionListenerStub(),
        SignedTransactionValidatorImpl,
        AccountServiceStub(Account(nonce, UInt256(signedTxType01.transaction.value))),
        resolveBlock,
        blockProvider,
        filterConfig
      )

    lazy val expectedFirstTransactionLogWithRemoved: TransactionLogWithRemoved = TransactionLogWithRemoved(
      removed = false,
      BigInt(0),
      BigInt(0),
      obftBlock.body.transactionList.head.hash,
      obftBlock.hash,
      BlockNumber(BigInt(1)),
      Address(42),
      Hex.decodeUnsafe("03" * 32),
      Seq(Hex.decodeUnsafe("01" * 32))
    )

    lazy val expectedSecondTransactionLogWithRemoved: TransactionLogWithRemoved =
      TransactionLogWithRemoved(
        removed = false,
        BigInt(0),
        BigInt(1),
        obftBlock.body.transactionList(1).hash,
        obftBlock.hash,
        BlockNumber(BigInt(1)),
        Address(42),
        Hex.decodeUnsafe("03" * 32),
        Seq(Hex.decodeUnsafe("01" * 32))
      )

  }
}
