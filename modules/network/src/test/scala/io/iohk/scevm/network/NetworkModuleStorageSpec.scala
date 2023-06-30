package io.iohk.scevm.network

import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.TransactionOutcome.HashOutcome
import io.iohk.scevm.domain.{
  Address,
  BlockHash,
  BlockNumber,
  LegacyReceipt,
  ObftHeader,
  ReceiptBody,
  TransactionLogEntry
}
import io.iohk.scevm.metrics.instruments.Histogram
import io.iohk.scevm.mpt.Generators.nodeGen
import io.iohk.scevm.mpt.MptTraversals.encodeNode
import io.iohk.scevm.testing.{BlockGenerators, IOSupport}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NetworkModuleStorageSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with IOSupport
    with IntegrationPatience {

  "NetworkModuleStorage" should "should return saved mpt node" in {
    val fixture   = new Fixture
    val n         = nodeGen.sample.get
    val nodeHash  = ByteString(n.hash)
    val nodeValue = encodeNode(n)
    fixture.stateStorage.saveNode(nodeHash, encodeNode(n))
    fixture.networkModuleStorage().getNode(nodeHash).ioValue shouldBe Some(ByteString(nodeValue))
  }

  it should "return saved receipt" in {
    val fakeReceipt = LegacyReceipt(
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

    val fixture   = new Fixture
    val blockHash = BlockHash(Hex.decodeUnsafe("123"))
    fixture.receiptStorage
      .putReceipts(
        blockHash,
        Seq(
          fakeReceipt
        )
      )
      .ioValue
    val networkStorage = fixture.networkModuleStorage()
    networkStorage.getReceiptsByHash(blockHash).ioValue shouldBe Some(List(fakeReceipt))
  }

  it should "return saved header" in {
    val fixture        = new Fixture
    val header         = BlockGenerators.obftBlockHeaderGen.sample.get
    val networkStorage = fixture.networkModuleStorage()
    networkStorage.insertHeader(header).ioValue
    networkStorage.getBlockHeader(header.hash).ioValue shouldBe Some(header)
  }

  it should "return saved block body" in {
    val fixture        = new Fixture
    val block          = BlockGenerators.obftBlockGen.sample.get
    val networkStorage = fixture.networkModuleStorage()
    networkStorage.insertBody(block.hash, block.body).ioValue
    networkStorage.getBlockBody(block.hash).ioValue shouldBe Some(block.body)
  }

  it should "return saved block" in {
    val fixture        = new Fixture
    val block          = BlockGenerators.obftBlockGen.sample.get
    val networkStorage = fixture.networkModuleStorage()
    networkStorage.insertBlock(block).ioValue
    networkStorage.getBlock(block.hash).ioValue shouldBe Some(block)
  }

  it should "return saved headers" in {
    val fixture = new Fixture
    val headers = LazyList
      .unfold(BlockGenerators.obftBlockHeaderGen.sample.get)(p =>
        BlockGenerators.obftHeaderChildGen(p).sample.map(c => (c, c))
      )
      .take(1)
      .toList
    val networkStorage = fixture.networkModuleStorage(getByNumberServiceStub = GetByNumberServiceStub(headers))
    headers.traverse(fixture.blockchainStorage.insertHeader).ioValue

    networkStorage
      .blockHeaders(headers.last)(headers.head.number, 0, headers.size, reverse = false)
      .ioValue shouldBe headers
  }

  class Fixture {
    private val source = EphemDataSource()
    val blockchainStorage: BlocksWriter[IO] with BlocksReader[IO] with BranchProvider[IO] =
      BlockchainStorage.unsafeCreate[IO](source)
    val stableNumberMappingStorage         = new StableNumberMappingStorageImpl[IO](source)
    val receiptStorage: ReceiptStorage[IO] = ReceiptStorage.unsafeCreate[IO](source)
    val stateStorage: StateStorage = StateStorage(
      new NodeStorage(source, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
    )
    val evmCodeStorage =
      new EvmCodeStorageImpl(source, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())

    val branchProvider: BranchProvider[IO] = new BranchProvider[IO] {
      override def getChildren(parent: BlockHash): IO[Seq[ObftHeader]]      = ???
      override def findSuffixesTips(parent: BlockHash): IO[Seq[ObftHeader]] = ???
      override def fetchChain(
          from: ObftHeader,
          to: ObftHeader
      ): IO[Either[BranchProvider.BranchRetrievalError, List[ObftHeader]]] = ???
    }

    def networkModuleStorage(
        getByNumberServiceStub: GetByNumberServiceStub = GetByNumberServiceStub(List.empty)
    ): NetworkModuleStorage[IO] = new NetworkModuleStorageImpl(
      stateStorage,
      evmCodeStorage,
      receiptStorage,
      blockchainStorage,
      getByNumberServiceStub,
      blockchainStorage,
      branchProvider
    )
  }

  case class GetByNumberServiceStub(headers: List[ObftHeader]) extends GetByNumberService[IO] {
    private val hashByNumber = headers.map(h => h.number -> h.hash).toMap
    override def getHashByNumber(branchTip: BlockHash)(number: BlockNumber): IO[Option[BlockHash]] =
      IO.delay(hashByNumber.get(number))
  }
}
