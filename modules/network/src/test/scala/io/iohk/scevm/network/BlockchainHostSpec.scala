package io.iohk.scevm.network

import cats.effect.IO
import fs2.Stream
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.{BranchProvider, EvmCodeStorage, ReceiptStorage, StateStorage}
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain._
import io.iohk.scevm.mpt.Generators.nodeGen
import io.iohk.scevm.mpt.Node.Hash
import io.iohk.scevm.network.p2p.messages.OBFT1._
import io.iohk.scevm.storage.execution.SlotBasedMempool
import io.iohk.scevm.testing.BlockGenerators.{
  blockHashGen,
  obftChainBlockWithoutTransactionsGen,
  obftChainHeaderGen,
  obftSmallBlockGen
}
import io.iohk.scevm.testing.{Generators, IOSupport, NormalPatience, TransactionGenerators, fixtures}
import org.scalacheck.Gen
import org.scalacheck.Shrink.shrinkAny
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.mutable.ListBuffer

class BlockchainHostSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  def blockHashWithReceiptsGen: Gen[(BlockHash, Seq[Receipt])] = for {
    hash     <- blockHashGen
    receipts <- Gen.listOf(Generators.receiptGen)
  } yield (hash, receipts)

  def blockHashWithReceiptsListGen: Gen[Map[BlockHash, Seq[Receipt]]] = Gen.mapOf(blockHashWithReceiptsGen)

  "BlockchainHost" when {
    "requested block receipts" should {

      "be soft limited in size by the configuration" in new TestSetup {
        forAll(blockHashWithReceiptsListGen, Generators.intGen(1, 2048)) {
          case (blockHash2ReceiptSeq, maxRequestedBlocksPerMessage) =>
            val hashes     = blockHash2ReceiptSeq.keySet.toSeq
            val hostConfig = hostConfiguration.copy(maxBlocksPerReceiptsMessage = maxRequestedBlocksPerMessage)

            val pipe = BlockchainHost.pipe(hostConfig, memPool, storage(recipes = blockHash2ReceiptSeq))

            val requestMessage = GetReceipts(hashes)

            val stream = Stream(peerId -> requestMessage)
              .covary[IO]
              .through(pipe)

            val result = stream.compile.toVector.ioValue
            result.size shouldBe 1 // always an answer
            result.head match {
              case (`peerId`, receipts: Receipts) =>
                receipts.receiptsForBlocks.size shouldBe Math.min(maxRequestedBlocksPerMessage, hashes.size)
              case response => fail(s"Invalid response: $response")
            }
        }
      }

      "contains all the requested known receipts" in new TestSetup {
        forAll(blockHashWithReceiptsListGen) { blockHash2ReceiptSamples =>
          forAll(Gen.someOf(blockHash2ReceiptSamples.keySet), Gen.someOf(blockHash2ReceiptSamples.keySet)) {
            case (blockHahesToStore, requestedHashes) =>
              val storedReceipts = blockHash2ReceiptSamples.view.filterKeys(blockHahesToStore.contains).toMap
              // no soft limit
              val hostConfig = hostConfiguration.copy(maxBlocksPerReceiptsMessage = requestedHashes.length)

              val pipe           = BlockchainHost.pipe(hostConfig, memPool, storage(recipes = storedReceipts))
              val requestMessage = GetReceipts(requestedHashes.toSeq)
              val test = for {
                result <- Stream(peerId -> requestMessage)
                            .covary[IO]
                            .through(pipe)
                            .compile
                            .toVector
              } yield {
                result.size shouldBe 1 // always an answer
                result.head match {
                  case (`peerId`, receipts: Receipts) =>
                    receipts.receiptsForBlocks shouldBe requestedHashes.flatMap(storedReceipts.get)
                  case response => fail(s"Invalid response: $response")
                }
              }

              test.ioValue
          }
        }
      }
    }

    "requested pooled transactions" should {
      "contain all the requested pooled transactions" in new TestSetup {
        forAll(TransactionGenerators.signedTxSeqGen()) { transactions =>
          forAll(Gen.someOf(transactions), Gen.someOf(transactions)) { (present, requested) =>
            val memPool        = SlotBasedMempoolStub[IO, SignedTransaction](ListBuffer.from(present))
            val requestMessage = GetPooledTransactions(requested.map(_.hash).toSeq)
            val requestId      = requestMessage.requestId
            val result = Stream(peerId -> requestMessage)
              .covary[IO]
              .through(BlockchainHost.pipe(hostConfiguration, memPool, storage()))
              .compile
              .toVector
              .ioValue
            result.headOption match {
              case Some(`peerId` -> PooledTransactions(`requestId`, txs)) => txs shouldBe present.intersect(requested)
              case result                                                 => fail(s"unexpected $result")
            }
          }
        }
      }
    }

    "requested block bodies" should {
      "be soft limited in size by the configuration" in new TestSetup {
        forAll(Gen.listOf(obftSmallBlockGen)) { blocks =>
          forAll(Generators.intGen(0, blocks.length)) { max =>
            val hostConfig                             = hostConfiguration.copy(maxBlocksBodiesPerMessage = max)
            val bodiesByHash: Map[BlockHash, ObftBody] = blocks.map(block => block.hash -> block.body).toMap
            val requestMessage                         = GetBlockBodies(blocks.map(_.hash))
            val requestId                              = requestMessage.requestId

            Stream(peerId -> requestMessage)
              .covary[IO]
              .through(BlockchainHost.pipe(hostConfig, memPool, storage(bodiesByHash = bodiesByHash)))
              .compile
              .toVector
              .ioValue
              .headOption match {
              case Some(`peerId` -> BlockBodies(`requestId`, bodies)) => bodies.length shouldBe max.min(blocks.length)
              case result                                             => fail(s"unexpected $result")
            }
          }
        }
      }

      "contain all the requested block bodies" in new TestSetup {
        forAll(Gen.listOf(obftSmallBlockGen)) { blocks =>
          forAll(Gen.someOf(blocks), Gen.someOf(blocks)) { (present, requested) =>
            val hostConfig     = hostConfiguration.copy(maxBlocksBodiesPerMessage = blocks.length)
            val bodiesByHash   = present.map(block => block.hash -> block.body).toMap
            val requestMessage = GetBlockBodies(requested.map(_.hash).toSeq)
            val requestId      = requestMessage.requestId

            Stream(peerId -> requestMessage)
              .covary[IO]
              .through(BlockchainHost.pipe(hostConfig, memPool, storage(bodiesByHash = bodiesByHash)))
              .compile
              .toVector
              .ioValue
              .headOption match {
              case Some(`peerId` -> BlockBodies(`requestId`, bodies)) =>
                bodies.map(_.toShortString) shouldBe requested.intersect(present).map(_.body.toShortString)
              case result => fail(s"unexpected $result")
            }
          }
        }
      }
    }

    "requested node data" should {
      "be soft limited in size by the configuration" in new TestSetup {
        forAll(Gen.listOf(nodeGen), Gen.listOf(Generators.evmCodeGen)) { (nodes, evmCodes) =>
          val numData = nodes.length + evmCodes.length
          forAll(Generators.intGen(0, numData)) { max =>
            val hostConfig     = hostConfiguration.copy(maxMptComponentsPerMessage = max)
            val nodesByHash    = nodes.map(node => ByteString(node.hash) -> node).toMap
            val evmCodesByHash = evmCodes.map(code => code -> code.encodeBase64).toMap

            val requestMessage = GetNodeData(nodes.map(node => ByteString(node.hash)) ++ evmCodes)
            val requestId      = requestMessage.requestId

            Stream(peerId -> requestMessage)
              .covary[IO]
              .through(
                BlockchainHost.pipe(
                  hostConfig,
                  memPool,
                  storage(nodes = nodesByHash.view.mapValues(v => ByteString(v.encode)).toMap ++ evmCodesByHash)
                )
              )
              .compile
              .toVector
              .ioValue
              .headOption match {
              case Some(`peerId` -> NodeData(`requestId`, blobs)) => blobs.length shouldBe max.min(numData)
              case result                                         => fail(s"unexpected $result")
            }
          }
        }
      }

      "contain all the requested blobs" in new TestSetup {
        forAll(Gen.listOf(nodeGen), Gen.listOf(Generators.evmCodeGen)) { (nodes, evmCodes) =>
          val numData = nodes.length + evmCodes.length
          forAll(Gen.someOf(nodes), Gen.someOf(evmCodes), Gen.someOf(nodes), Gen.someOf(evmCodes)) {
            (presentNodes, presentEvmCodes, requestedNodes, requestedEvmCodes) =>
              val hostConfig     = hostConfiguration.copy(maxMptComponentsPerMessage = numData)
              val nodesByHash    = presentNodes.map(node => ByteString(node.hash) -> node).toMap
              val evmCodesByHash = presentEvmCodes.map(code => code -> code.encodeBase64).toMap

              val present = presentNodes.map(node => ByteString(node.encode)) ++
                presentEvmCodes.map(_.encodeBase64)
              val requested = requestedNodes.map(node => ByteString(node.encode)) ++
                requestedEvmCodes.map(_.encodeBase64)
              val requestMessage = GetNodeData(
                (requestedNodes.map(node => ByteString(node.hash)) ++ requestedEvmCodes).toSeq
              )
              val requestId = requestMessage.requestId

              Stream(peerId -> requestMessage)
                .covary[IO]
                .through(
                  BlockchainHost.pipe(
                    hostConfig,
                    memPool,
                    storage(nodes = nodesByHash.view.mapValues(v => ByteString(v.encode)).toMap ++ evmCodesByHash)
                  )
                )
                .compile
                .toVector
                .ioValue
                .headOption match {
                case Some(`peerId` -> NodeData(`requestId`, blobs)) => blobs shouldBe requested.intersect(present)
                case result                                         => fail(s"unexpected $result")
              }
          }
        }
      }
    }

    "requested block headers" should {

      val headerChainLength = 512

      "be soft limited in size by the configuration" in new TestSetup {
        // test: chain of size x, request of y, conf of z -> returned size == min(x, y, z)
        forAll(
          obftChainHeaderGen(1, headerChainLength).suchThat(_.nonEmpty),
          Generators.intGen(1, headerChainLength),
          Generators.intGen(1, headerChainLength)
        ) { case (obftChainHeaders, requestedBlockHeaders, maxRequestedBlockHeaders) =>
          val hostConfig = hostConfiguration.copy(maxBlocksHeadersPerMessage = maxRequestedBlockHeaders)

          implicit val current: CurrentBranch.Signal[IO] =
            Signal.constant(CurrentBranch(obftChainHeaders.last, genesis))

          val pipe = BlockchainHost.pipe(hostConfig, memPool, storage(obftChainHeaders = obftChainHeaders))

          val requestMessage =
            GetBlockHeaders(Left(obftChainHeaders.head.number), requestedBlockHeaders, 0, reverse = false)

          val stream = Stream(peerId -> requestMessage)
            .covary[IO]
            .through(pipe)

          val result = stream.compile.toVector.ioValue
          result.size shouldBe 1 // always an answer
          result.head match {
            case (`peerId`, blockHeaders: BlockHeaders) =>
              val expected = List(obftChainHeaders.size, maxRequestedBlockHeaders, requestedBlockHeaders).min
              blockHeaders.headers.size shouldBe expected
            case response => fail(s"Invalid response: $response")
          }
        }
      }

      "be returned when requested by number" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Left(obftChainHeaders.head.number), requestedBlockHeaderCount, 0, reverse = false),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.take(requestedBlockHeaderCount)
      )

      "be returned when requested by hash" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Right(obftChainHeaders.head.hash), requestedBlockHeaderCount, 0, reverse = false),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.take(requestedBlockHeaderCount)
      )

      "stop at end of chain when requested by number" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Left(obftChainHeaders.last.number), requestedBlockHeaderCount, 0, reverse = false),
        (obftChainHeaders, _, blockHeaders) => blockHeaders.headers shouldBe List(obftChainHeaders.last)
      )

      "stop at end of chain when requested by hash" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Right(obftChainHeaders.last.hash), requestedBlockHeaderCount, 0, reverse = false),
        (obftChainHeaders, _, blockHeaders) => blockHeaders.headers shouldBe List(obftChainHeaders.last)
      )

      "be returned when requested by number with skip" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Left(obftChainHeaders.head.number), requestedBlockHeaderCount, 2, reverse = false),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.grouped(3).map(_.head).take(requestedBlockHeaderCount).toList
      )

      "be returned when requested by hash with skip" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Right(obftChainHeaders.head.hash), requestedBlockHeaderCount, 2, reverse = false),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.grouped(3).map(_.head).take(requestedBlockHeaderCount).toList
      )

      "be returned when requested by number with reverse" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Left(obftChainHeaders.last.number), requestedBlockHeaderCount, 0, reverse = true),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.takeRight(requestedBlockHeaderCount).reverse
      )

      "be returned when requested by hash with reverse" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Right(obftChainHeaders.last.hash), requestedBlockHeaderCount, 0, reverse = true),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.takeRight(requestedBlockHeaderCount).reverse
      )

      "stops at genesis when requested by number with reverse" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Left(obftChainHeaders.head.number), requestedBlockHeaderCount, 0, reverse = true),
        (obftChainHeaders, _, blockHeaders) => blockHeaders.headers shouldBe List(obftChainHeaders.head)
      )

      "stops at genesis when requested by hash with reverse" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Right(obftChainHeaders.head.hash), requestedBlockHeaderCount, 0, reverse = true),
        (obftChainHeaders, _, blockHeaders) => blockHeaders.headers shouldBe List(obftChainHeaders.head)
      )

      "be returned when requested by number with reverse and skip" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Left(obftChainHeaders.last.number), requestedBlockHeaderCount, 2, reverse = true),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.reverse
            .grouped(3)
            .map(_.head)
            .take(requestedBlockHeaderCount)
            .toList
      )

      "be returned when requested by hash with reverse and skip" in testBlockHeadersProperties(
        headerChainLength,
        (obftChainHeaders: List[ObftHeader], requestedBlockHeaderCount) =>
          GetBlockHeaders(Right(obftChainHeaders.last.hash), requestedBlockHeaderCount, 2, reverse = true),
        (obftChainHeaders, requestedBlockHeaderCount, blockHeaders) =>
          blockHeaders.headers shouldBe obftChainHeaders.reverse
            .grouped(3)
            .map(_.head)
            .take(requestedBlockHeaderCount)
            .toList
      )
    }

    "request stable block" should {

      val headerChainLength = 10

      "return stable block" in new TestSetup {
        // at least 5 blocks long chain because stable + ancestor 4 blocks ahead
        forAll(obftChainHeaderGen(5, headerChainLength).suchThat(_.size > 4)) { obftChainHeaders =>
          implicit val current: CurrentBranch.Signal[IO] =
            Signal.constant(CurrentBranch(obftChainHeaders.last, genesis))

          val pipe = BlockchainHost.pipe(
            hostConfiguration.copy(maxStableHeadersPerMessage = headerChainLength),
            memPool,
            storage(obftChainHeaders)
          )

          val ancestorsOffsets = Seq(1, 2, 4)
          val requestMessage   = GetStableHeaders(ancestorsOffsets)

          val stream = Stream(peerId -> requestMessage)
            .covary[IO]
            .through(pipe)

          val result = stream.compile.toVector.ioValue
          result.size shouldBe 1 // always an answer
          result.head match {
            case (`peerId`, StableHeaders(_, header, ancestors)) =>
              header shouldBe obftChainHeaders.last
              val expected =
                ancestorsOffsets
                  .map(offset => obftChainHeaders.dropRight(offset).last)
                  .filter(_.number >= BlockNumber(0))
              ancestors shouldBe expected
            case response => fail(s"Invalid response: $response")
          }
        }
      }

      "return genesis if no other stable block present" in new TestSetup {
        val pipe = BlockchainHost.pipe(hostConfiguration, memPool, storage())

        val requestMessage = GetStableHeaders(Seq(1, 2, 3))

        val stream = Stream(peerId -> requestMessage)
          .covary[IO]
          .through(pipe)

        val result = stream.compile.toVector.ioValue
        result.size shouldBe 1 // always an answer
        result.head match {
          case (`peerId`, StableHeaders(_, header, ancestors)) =>
            header shouldBe genesis
            ancestors shouldBe empty
          case response => fail(s"Invalid response: $response")
        }
      }
    }

    "request full blocks" should {
      val maxChainLength = 100
      "return full blocks (0 < numRequestedBlocks <= numReturnedBlocksLimit <= chainLength)" in new TestSetup {
        forAll(for {
          chainLength             <- Gen.choose(1, maxChainLength)
          numRequestedBlocksLimit <- Gen.choose(1, chainLength)
          numRequestedBlocks      <- Gen.choose(1, numRequestedBlocksLimit)
          chain                   <- obftChainBlockWithoutTransactionsGen(chainLength, chainLength)
        } yield (numRequestedBlocksLimit, numRequestedBlocks, chain)) {
          case (numRequestedBlocksLimit, numRequestedBlocks, chain) =>
            val tip = chain.last

            implicit val current: CurrentBranch.Signal[IO] =
              Signal.constant(CurrentBranch(tip.header, tip.header))

            val pipe = BlockchainHost.pipe(
              hostConfiguration.copy(maxBlocksPerFullBlocksMessage = numRequestedBlocksLimit),
              memPool,
              storage(
                obftChainHeaders = chain.map(_.header),
                bodiesByHash = chain.map { block =>
                  block.header.hash -> block.body
                }.toMap
              )
            )

            val requestMessage = GetFullBlocks(tip.hash, numRequestedBlocks)

            val result = Stream(peerId -> requestMessage)
              .covary[IO]
              .through(pipe)
              .compile
              .toVector
              .ioValue

            result.size shouldBe 1
            result.head match {
              case (`peerId`, FullBlocks(_, blocks)) =>
                blocks.size shouldBe numRequestedBlocks
                blocks.sameElements(chain)
              case response => fail(s"Invalid response: $response")
            }
        }
      }

      "limit the result set if requested too many blocks (0 < numReturnedBlocksLimit <= numRequestedBlocks <= chainLength)" in new TestSetup {
        forAll(for {
          chainLength             <- Gen.choose(2, maxChainLength)
          numRequestedBlocksLimit <- Gen.choose(1, chainLength - 1)
          numRequestedBlocks      <- Gen.choose(numRequestedBlocksLimit + 1, maxChainLength)
          chain                   <- obftChainBlockWithoutTransactionsGen(chainLength, chainLength)
        } yield (numRequestedBlocksLimit, numRequestedBlocks, chain)) {
          case (numRequestedBlocksLimit, numRequestedBlocks, chain) =>
            val tip = chain.last

            implicit val current: CurrentBranch.Signal[IO] =
              Signal.constant(CurrentBranch(tip.header, tip.header))

            val pipe = BlockchainHost.pipe(
              hostConfiguration.copy(maxBlocksPerFullBlocksMessage = numRequestedBlocksLimit),
              memPool,
              storage(
                obftChainHeaders = chain.map(_.header),
                bodiesByHash = chain.map { block =>
                  block.header.hash -> block.body
                }.toMap
              )
            )

            val requestMessage = GetFullBlocks(tip.hash, numRequestedBlocks)

            val result = Stream(peerId -> requestMessage)
              .covary[IO]
              .through(pipe)
              .compile
              .toVector
              .ioValue

            result.size.shouldBe(1)
            result.head match {
              case (`peerId`, FullBlocks(_, blocks)) =>
                blocks.size.shouldBe(numRequestedBlocksLimit)
                blocks.sameElements(chain)
              case response => fail(s"Invalid response: $response")
            }
        }
      }

      "return an empty list of blocks if the target block was not found" in new TestSetup {
        forAll(for {
          chainLength             <- Gen.choose(1, maxChainLength)
          numRequestedBlocksLimit <- Gen.choose(1, chainLength)
          numRequestedBlocks      <- Gen.choose(1, numRequestedBlocksLimit)
          chain                   <- obftChainBlockWithoutTransactionsGen(chainLength, chainLength)
        } yield (numRequestedBlocksLimit, numRequestedBlocks, chain)) {
          case (numRequestedBlocksLimit, numRequestedBlocks, chain) =>
            val tip = chain.last

            implicit val current: CurrentBranch.Signal[IO] =
              Signal.constant(CurrentBranch(tip.header, tip.header))

            val pipe = BlockchainHost.pipe(
              hostConfiguration.copy(maxBlocksBodiesPerMessage = numRequestedBlocksLimit),
              memPool,
              storage()
            )

            val requestMessage = GetFullBlocks(tip.hash, numRequestedBlocks)

            val result = Stream(peerId -> requestMessage)
              .covary[IO]
              .through(pipe)
              .compile
              .toVector
              .ioValue

            result.size shouldBe 1
            result.head match {
              case (`peerId`, FullBlocks(_, blocks)) =>
                blocks.size shouldBe 0
              case response => fail(s"Invalid response: $response")
            }
        }
      }
    }
  }
  //scalastyle:off method.length
  private def testBlockHeadersProperties(
      headerChainLength: Int,
      messageBuilder: (List[ObftHeader], Int) => GetBlockHeaders,
      resultValidator: (List[ObftHeader], Int, BlockHeaders) => Unit
  ): Unit = {
    val setup = new TestSetup

    forAll(
      obftChainHeaderGen(2, headerChainLength).suchThat(_.nonEmpty),
      Generators.intGen(2, headerChainLength)
    ) { case (obftChainHeaders, requestedBlockHeaders) =>
      val hostConfig = HostConfig(
        maxBlocksHeadersPerMessage = requestedBlockHeaders,
        maxBlocksBodiesPerMessage = 1,
        maxBlocksPerReceiptsMessage = 1,
        maxBlocksPerFullBlocksMessage = 1,
        maxStableHeadersPerMessage = 1,
        maxMptComponentsPerMessage = 1,
        parallelism = 1
      )
      implicit val current: CurrentBranch.Signal[IO] =
        Signal.constant(CurrentBranch(obftChainHeaders.last, setup.genesis))

      val pipe = BlockchainHost.pipe(
        hostConfig,
        setup.memPool,
        setup.storage(obftChainHeaders = obftChainHeaders)
      )

      val stream = Stream(setup.peerId -> messageBuilder(obftChainHeaders, requestedBlockHeaders))
        .covary[IO]
        .through(pipe)

      val result = stream.compile.toVector.ioValue
      result.size shouldBe 1 // always an answer
      result.head match {
        case (_, blockHeaders: BlockHeaders) =>
          resultValidator(obftChainHeaders, requestedBlockHeaders, blockHeaders)
        case (_, response) => throw new RuntimeException(s"Invalid response, expected BlockHeaders, got $response")
      }
    }
  }

  //scalastyle:on method.length
  private class TestSetup {
    val genesis: ObftHeader                              = fixtures.GenesisBlock.header
    lazy val hostConfiguration: HostConfig               = HostConfig(0, 0, 0, 0, 0, 0, 1)
    implicit val current: CurrentBranch.Signal[IO]       = Signal.constant(CurrentBranch(genesis, genesis))
    val memPool: SlotBasedMempool[IO, SignedTransaction] = SlotBasedMempoolStub(ListBuffer.empty)
    val peerId: PeerId                                   = PeerId("testPeer")

    def storage(
        obftChainHeaders: List[ObftHeader] = List.empty,
        recipes: Map[BlockHash, Seq[Receipt]] = Map.empty,
        bodiesByHash: Map[BlockHash, ObftBody] = Map.empty,
        nodes: Map[ByteString, ByteString] = Map.empty
    ) =
      new NetworkStorageStub(obftChainHeaders, recipes, bodiesByHash, nodes)
  }

  private class NetworkStorageStub(
      obftChainHeaders: List[ObftHeader],
      recipes: Map[BlockHash, Seq[Receipt]],
      bodiesByHash: Map[BlockHash, ObftBody],
      nodes: Map[ByteString, ByteString]
  ) extends NetworkModuleStorage[IO] {
    private val number2hash  = obftChainHeaders.map(header => header.number -> header.hash).toMap
    private val hashToHeader = obftChainHeaders.map(header => header.hash -> header).toMap

    override def getNode(nodeHash: Hash): IO[Option[ByteString]] = IO.delay(nodes.get(nodeHash))

    override def getReceiptsByHash(hash: BlockHash): IO[Option[Seq[Receipt]]] = IO.delay(recipes.get(hash))

    override def blockHeaders(
        branchTip: ObftHeader
    )(blockNumber: BlockNumber, skip: Int, limit: Int, reverse: Boolean): IO[List[ObftHeader]] = IO(
      getRange(blockNumber, skip, limit, reverse)
        .flatMap(number2hash.get)
        .flatMap(hashToHeader.get)
    )

    private def getRange(blockNumber: BlockNumber, skip: Int, limit: Int, reverse: Boolean): List[BlockNumber] =
      LazyList
        .iterate(blockNumber)(_ + BlocksDistance((skip + 1) * (if (reverse) -1 else 1)))
        .take(limit)
        .toList

    override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] =
      IO.delay(hashToHeader.get(hash))

    override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]] = IO.delay(bodiesByHash.get(hash))

    override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] = IO.pure(None)

    override def insertHeader(header: ObftHeader): IO[Unit] = IO.unit

    override def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit

    override def getHeaderByNumber(tip: ObftHeader, number: BlockNumber): IO[Option[ObftHeader]] =
      IO.pure(obftChainHeaders.find(_.number == number))

    override def getStateStorage: StateStorage = ???

    override def getCodeStorage: EvmCodeStorage = ???

    override def getReceiptStorage: ReceiptStorage[IO] = ???

    override def getBranchProvider: BranchProvider[IO] = ???
  }
}
