package io.iohk.scevm.consensus.domain

import cats.Applicative
import cats.effect.IO
import cats.implicits.showInterpolator
import io.iohk.scevm.domain
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.ledger.{BlockProvider, BlockProviderStub}
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import ForwardBranchSlice.ForwardBranchSliceError

class ForwardBranchSliceSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  // memory implementation if size < threshold
  "ForwardBranchSlice.build" should "return a Memory implementation if range is smaller or equals than maxHeadersInMemoryCount" in {
    forAll(BlockGenerators.obftChainBlockWithoutTransactionsGen(1, 50)) { blocksList =>
      val blockProvider           = buildBlockProvider(blocksList)
      val maxHeadersInMemoryCount = blocksList.size
      val branchSliceResult =
        ForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, blocksList.head.hash, blocksList.last.hash)

      branchSliceResult.ioValue match {
        case Left(error) => fail(error.toString)
        case Right(branchSlice) =>
          branchSlice shouldBe an[HeadersInMemoryForwardBranchSlice[IO]]
      }
    }
  }

  // storage implementation if size >= threshold
  it should "return a Storage implementation if range is greater than maxHeadersInMemoryCount" in {
    forAll(BlockGenerators.obftChainBlockWithoutTransactionsGen(2, 50)) { blocksList =>
      val blockProvider           = buildBlockProvider(blocksList)
      val maxHeadersInMemoryCount = blocksList.size - 1
      val branchSliceResult =
        ForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, blocksList.head.hash, blocksList.last.hash)

      branchSliceResult.ioValue match {
        case Left(error) => fail(error.toString)
        case Right(branchSlice) =>
          branchSlice shouldBe an[StorageForwardBranchSlice[IO]]
      }
    }
  }

  // error if from > to
  it should "return an error if from is bigger than to" in {
    forAll(BlockGenerators.obftChainBlockWithoutTransactionsGen(2, 50)) { blocksList =>
      val blockProvider           = buildBlockProvider(blocksList)
      val maxHeadersInMemoryCount = blocksList.size
      val branchSliceResult =
        ForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, blocksList.last.hash, blocksList.head.hash)

      branchSliceResult.ioValue match {
        case Left(ForwardBranchSliceError.InvalidBoundariesOrder(from, to)) =>
          from shouldBe blocksList.last.header
          to shouldBe blocksList.head.header
        case unexpected =>
          fail(s"An InvalidBoundariesOrder error should have happened instead of $unexpected")
      }
    }
  }

  // error if disconnected branch
  it should "return an error if from is not on the same branch than to" in {
    testDisconnectedBranch { case (blockProvider, blocksList, disconnectedBlock) =>
      val maxHeadersInMemoryCount = blocksList.size
      ForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, disconnectedBlock.hash, blocksList.last.hash)
    }
  }

  // error if any missing header
  it should "return an error if the any header is missing" in {
    testHandleMissingHeader { case (blockProvider, headersList) =>
      val maxHeadersInMemoryCount = headersList.size
      ForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, headersList.head.hash, headersList.last.hash)
    }
  }

  // memory: returns in the proper order
  "MemoryForwardBranchSlice" should "streams the same elements in the correct order" in {
    testSameElementsInCorrectOrder { case (blockProvider, blocksList) =>
      HeadersInMemoryForwardBranchSlice.build(blockProvider, blocksList.head.header, blocksList.last.header)
    }
  }

  // memory: handle missing header
  it should "return an error if the any header is missing" in {
    testHandleMissingHeader { case (blockProvider, blocksList) =>
      HeadersInMemoryForwardBranchSlice.build(blockProvider, blocksList.head.header, blocksList.last.header)
    }
  }

  // memory: error if disconnected branch
  it should "return an error if from is not on the same branch than to" in {
    testDisconnectedBranch { case (blockProvider, blocksList, disconnectedBlock) =>
      HeadersInMemoryForwardBranchSlice.build(blockProvider, disconnectedBlock.header, blocksList.last.header)
    }
  }

  // storage: returns in the proper order
  "StorageForwardBranchSlice" should "streams the same elements in the correct order" in {
    testSameElementsInCorrectOrder { case (blockProvider, blocksList) =>
      val maxHeadersInMemoryCount = blocksList.size
      StorageForwardBranchSlice.build(
        maxHeadersInMemoryCount,
        blockProvider,
        blocksList.head.header,
        blocksList.last.header
      )
    }
  }

  // storage: handle missing header
  it should "return an error if the any header is missing" in {
    testHandleMissingHeader { case (blockProvider, headersList) =>
      val maxHeadersInMemoryCount = headersList.size
      StorageForwardBranchSlice.build(
        maxHeadersInMemoryCount,
        blockProvider,
        headersList.head.header,
        headersList.last.header
      )
    }
  }

  // storage: error if disconnected branch
  it should "return an error if from is not on the same branch than to" in {
    testDisconnectedBranch { case (blockProvider, blocksList, disconnectedBlock) =>
      val maxHeadersInMemoryCount = blocksList.size / 2
      StorageForwardBranchSlice.build(
        maxHeadersInMemoryCount,
        blockProvider,
        disconnectedBlock.header,
        blocksList.last.header
      )
    }
  }

  // storage: handle disappearing header
  it should "handle disappearing headers during processing" in {
    forAll(BlockGenerators.obftChainBlockWithoutTransactionsGen(4, 50)) { bocksList =>
      val blockProvider           = new MutableStubBlockProvider[IO](bocksList)
      val maxHeadersInMemoryCount = bocksList.size / 2
      val branchSliceResult =
        StorageForwardBranchSlice.build(
          maxHeadersInMemoryCount,
          blockProvider,
          bocksList.head.header,
          bocksList.last.header
        )

      branchSliceResult.ioValue match {
        case Left(error) => fail(s"unexpected error $error")
        case Right(branchSlice) =>
          blockProvider.removeBlock(bocksList.head)
          val e = intercept[RuntimeException](
            branchSlice.headerStream.compile.toList.unsafeRunSync()
          )
          e.getMessage shouldBe s"unable to load header for hash ${bocksList.head.hash}"
      }
    }
  }

  // toBlock: should returns blocks in the proper order
  "ForwardBranchSlice.toBlock" should "return the blocks in the proper order" in {
    forAll(BlockGenerators.obftChainBlockWithGen(1, 20, BlockGenerators.obftEmptyBodyBlockGen)) { blocksList =>
      val blockProvider           = buildBlockProvider(blocksList)
      val maxHeadersInMemoryCount = 10
      val branchSliceResult =
        ForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, blocksList.head.hash, blocksList.last.hash)

      branchSliceResult.ioValue match {
        case Left(error) => fail(error.toString)
        case Right(branchSlice) =>
          branchSlice.blockStream.compile.toList.ioValue shouldBe blocksList
          branchSlice.range.from shouldBe blocksList.head.hash
          branchSlice.range.to shouldBe blocksList.last.hash
      }
    }
  }

  class MutableStubBlockProvider[F[_]: Applicative](blocksList: List[ObftBlock]) extends BlockProvider[F] {

    private var hash2Block = blocksList.map(h => (h.hash, h)).toMap

    def removeBlock(block: ObftBlock): Unit =
      hash2Block = hash2Block.removed(block.hash)

    override def getHeader(hash: BlockHash): F[Option[ObftHeader]] =
      Applicative[F].pure(hash2Block.get(hash).map(_.header))

    override def getBody(hash: BlockHash): F[Option[ObftBody]] = ???

    override def getBlock(hash: BlockHash): F[Option[ObftBlock]] = Applicative[F].pure(hash2Block.get(hash))

    override def getBlock(number: domain.BlockNumber): F[Option[ObftBlock]] = ???

    override def getHeader(number: domain.BlockNumber): F[Option[ObftHeader]] = ???

    override def getBody(number: domain.BlockNumber): F[Option[ObftBody]] = ???

    override def getBestBlock: F[ObftBlock] = ???

    override def getBestBlockHeader: F[ObftHeader] = ???

    override def getStableBlock: F[ObftBlock] = ???

    override def getStableBlockHeader: F[ObftHeader] = ???

    override def getHashByNumber(branchTip: ObftHeader)(number: domain.BlockNumber): F[Option[BlockHash]] = ???
  }

  private def testSameElementsInCorrectOrder(
      buildSlice: (BlockProvider[IO], List[ObftBlock]) => IO[Either[ForwardBranchSliceError, ForwardBranchSlice[IO]]]
  ): Unit =
    forAll(BlockGenerators.obftChainBlockWithoutTransactionsGen(1, 10)) { blocksList =>
      val blockProvider     = buildBlockProvider(blocksList)
      val branchSliceResult = buildSlice(blockProvider, blocksList)

      branchSliceResult.ioValue match {
        case Left(error) => fail(error.toString)
        case Right(branchSlice) =>
          branchSlice.headerStream.compile.toList.ioValue shouldBe blocksList.map(_.header)
          branchSlice.blockStream.compile.toList.ioValue shouldBe blocksList
          branchSlice.range.from shouldBe blocksList.head.hash
          branchSlice.range.to shouldBe blocksList.last.hash
      }
    }

  private def testHandleMissingHeader(
      buildSlice: (BlockProvider[IO], List[ObftBlock]) => IO[Either[ForwardBranchSliceError, ForwardBranchSlice[IO]]]
  ): Unit = {

    val generatedData = for {
      blocksList         <- BlockGenerators.obftChainBlockWithoutTransactionsGen(1, 50)
      missingHeaderIndex <- Gen.choose(0, blocksList.length - 1)
    } yield (blocksList, missingHeaderIndex)

    forAll(generatedData) { case (blocksList, missingHeaderIndex) =>
      val missingHeader     = blocksList(missingHeaderIndex)
      val blockProvider     = buildBlockProvider(blocksList.filter(_.hash != missingHeader.hash))
      val branchSliceResult = buildSlice(blockProvider, blocksList)

      branchSliceResult.ioValue match {
        case Left(ForwardBranchSliceError.MissingHeader(hash)) =>
          hash shouldBe missingHeader.hash
          show"${ForwardBranchSliceError.MissingHeader(hash)}"
        case _ =>
          fail("A MissingHeader error should have happened")
      }
    }
  }

  private def testDisconnectedBranch(
      buildSlice: (
          BlockProvider[IO],
          List[ObftBlock],
          ObftBlock
      ) => IO[Either[ForwardBranchSliceError, ForwardBranchSlice[IO]]]
  ): Unit =
    forAll(BlockGenerators.obftChainBlockWithoutTransactionsGen(2, 50)) { blocksList =>
      val sampleBlock       = BlockGenerators.obftEmptyBodyBlockGen.sample.get
      val disconnectedBlock = sampleBlock.copy(header = sampleBlock.header.copy(number = blocksList.head.number))
      val blockProvider     = buildBlockProvider(blocksList :+ disconnectedBlock)
      val branchSliceResult = buildSlice(blockProvider, blocksList, disconnectedBlock)

      branchSliceResult.ioValue match {
        case Left(ForwardBranchSliceError.DisconnectedBranch(expectedFrom, from, to)) =>
          expectedFrom shouldBe disconnectedBlock.header
          from shouldBe blocksList.head.header
          to shouldBe blocksList.last.header
        case unexpected =>
          fail(s"An DisconnectedBranch error should have happened instead of $unexpected")
      }
    }

  private def buildBlockProvider(blocksList: List[ObftBlock]): BlockProvider[IO] = {
    val randomBlock = BlockGenerators.obftEmptyBodyBlockGen.sample.get
    BlockProviderStub(
      blocksList,
      blocksList.lastOption.getOrElse(randomBlock),
      blocksList.lastOption.getOrElse(randomBlock)
    )
  }
}
