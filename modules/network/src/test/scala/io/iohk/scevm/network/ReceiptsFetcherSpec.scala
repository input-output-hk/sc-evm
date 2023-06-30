package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.implicits.{showInterpolator, toTraverseOps}
import io.iohk.scevm.db.storage.ReceiptStorage
import io.iohk.scevm.domain.{BlockHash, Receipt}
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.network.Generators.peerWithInfoGen
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.{Gen, Shrink}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.reflect.ClassTag

class ReceiptsFetcherSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  "ReceiptsFetcher" should {
    "fetch receipts with `GetReceipts` messages where each request is capped by `maxReceiptsPerRequest`" in {
      forAll {
        for {
          numBlocks   <- Gen.choose(10, 100)
          headers     <- Gen.listOfN(numBlocks, io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen)
          allReceipts <- io.iohk.scevm.testing.Generators.receiptsGen(numBlocks)
          receiptsMap = headers
                          .zip(allReceipts)
                          .map { case (header, blockReceipts) =>
                            val root = MptStorage.rootHash(blockReceipts, Receipt.byteArraySerializable)
                            header.copy(receiptsRoot = root) -> blockReceipts
                          }
                          .toMap
          maxReceiptsPerRequest <- Gen.choose(1, numBlocks)
          peerWithWithInfo      <- peerWithInfoGen
        } yield (receiptsMap, peerWithWithInfo, maxReceiptsPerRequest)
      } { case (receiptsMap, peerWithWithInfo, maxReceiptsPerRequest) =>
        val test = for {
          fetchedReceiptsRef <- Ref.of[IO, Map[BlockHash, List[Receipt]]](Map.empty)

          receiptsByHash  = receiptsMap.map { case (header, receipts) => header.hash -> receipts }
          mockPeerChannel = createMockChannel(receiptsByHash, maxReceiptsPerRequest)
          mockReceiptStorage = new ReceiptStorage[IO] {
                                 override def getReceiptsByHash(hash: BlockHash): IO[Option[Seq[Receipt]]] =
                                   IO.raiseError(new RuntimeException("Should not be called"))

                                 override def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): IO[Unit] =
                                   fetchedReceiptsRef.update(_ + (hash -> receipts.toList))
                               }
          receiptsFetcher = new ReceiptsFetcherImpl[IO](
                              mockPeerChannel,
                              mockReceiptStorage,
                              maxReceiptsPerRequest
                            )

          allBlockHashes   = receiptsMap.keys.toList
          _               <- receiptsFetcher.fetchReceipts(allBlockHashes, NonEmptyList.of(peerWithWithInfo.id))
          fetchedReceipts <- fetchedReceiptsRef.get
        } yield assert(fetchedReceipts == receiptsByHash)

        test.ioValue
      }
    }

    "fail with 'AllPeersDisconnected' if unable to download receipts" in {
      forAll {
        for {
          numBlocks             <- Gen.choose(10, 100)
          headers               <- Gen.listOfN(numBlocks, io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen)
          receipts              <- io.iohk.scevm.testing.Generators.receiptsGen(headers.size)
          receiptsMap            = headers.zip(receipts).toMap
          maxReceiptsPerRequest <- Gen.choose(1, numBlocks)
          peerWithWithInfo      <- peerWithInfoGen
        } yield (receiptsMap, peerWithWithInfo, maxReceiptsPerRequest)
      } { case (receiptsMap, peerWithWithInfo, maxReceiptsPerRequest) =>
        val mockPeerChannel = new PeerChannel[IO] {
          override def askPeers[Response <: OBFT1.ResponseMessage: ClassTag](
              peerIds: NonEmptyList[PeerId],
              requestMessage: OBFT1.RequestMessage
          ): IO[Either[PeerChannel.MultiplePeersAskFailure, Response]] = IO.pure(
            Left(PeerChannel.MultiplePeersAskFailure.AllPeersTimedOut)
          )
        }
        val receiptsFetcher = new ReceiptsFetcherImpl[IO](
          mockPeerChannel,
          unusedReceiptStorage,
          maxReceiptsPerRequest
        )

        val allBlockHashes = receiptsMap.keys.toList
        receiptsFetcher
          .fetchReceipts(allBlockHashes, NonEmptyList.of(peerWithWithInfo.id))
          .map(fetchResult => assert(fetchResult == Left(ReceiptsFetcher.ReceiptsFetchError.AllPeersTimedOut)))
          .ioValue
      }
    }

    "fail with 'InvalidResponse' if the peer returned less receipts than requested" in {
      forAll {
        for {
          numBlocks             <- Gen.choose(10, 100)
          headers               <- Gen.listOfN(numBlocks, io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen)
          blockHashes            = headers.map(_.hash)
          receipts              <- io.iohk.scevm.testing.Generators.receiptsGen(blockHashes.size)
          receiptsMap            = blockHashes.zip(receipts).toMap
          maxReceiptsPerRequest <- Gen.choose(1, numBlocks)
          peerWithWithInfo      <- peerWithInfoGen
        } yield (receiptsMap, headers, peerWithWithInfo, maxReceiptsPerRequest)
      } { case (receiptsMap, headers, peerWithWithInfo, maxReceiptsPerRequest) =>
        val mockPeerChannel: PeerChannel[IO] = new PeerChannel[IO] {
          override def askPeers[Response <: OBFT1.ResponseMessage: ClassTag](
              peerIds: NonEmptyList[PeerId],
              requestMessage: OBFT1.RequestMessage
          ): IO[Either[PeerChannel.MultiplePeersAskFailure, Response]] = requestMessage match {
            case OBFT1.GetReceipts(requestId, blockHashes) =>
              blockHashes.toList.traverse(receiptsMap.get) match {
                case Some(receipts) =>
                  IO.pure(
                    Right[PeerChannel.MultiplePeersAskFailure, Response](
                      OBFT1.Receipts(requestId, receipts.drop(1)).asInstanceOf[Response]
                    )
                  )
                case None =>
                  IO.raiseError(new RuntimeException("Receipts not found"))
              }
            case _ => IO.raiseError(new RuntimeException("Unexpected request message"))
          }
        }

        val receiptsFetcher = new ReceiptsFetcherImpl[IO](
          mockPeerChannel,
          unusedReceiptStorage,
          maxReceiptsPerRequest
        )

        receiptsFetcher
          .fetchReceipts(headers, NonEmptyList.of(peerWithWithInfo.id))
          .map(fetchResult => assert(fetchResult == Left(ReceiptsFetcher.ReceiptsFetchError.InvalidResponse)))
          .ioValue
      }
    }

    "fail with 'InvalidReceipts' if the receiptsRoot is not equal to header.receiptsRoot" in {
      forAll {
        for {
          numBlocks   <- Gen.choose(10, 100)
          headers     <- Gen.listOfN(numBlocks, io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen)
          allReceipts <- io.iohk.scevm.testing.Generators.receiptsGen(numBlocks)
          receiptsMap = headers
                          .zip(allReceipts) // headers receiptsRoot is "empty", making it invalid
                          .toMap
          maxReceiptsPerRequest <- Gen.choose(1, numBlocks)
          peerWithWithInfo      <- peerWithInfoGen
        } yield (receiptsMap, peerWithWithInfo, maxReceiptsPerRequest)
      } { case (receiptsMap, peerWithWithInfo, maxReceiptsPerRequest) =>
        val receiptsByHash  = receiptsMap.map { case (header, receipts) => header.hash -> receipts }
        val mockPeerChannel = createMockChannel(receiptsByHash, maxReceiptsPerRequest)
        val receiptsFetcher = new ReceiptsFetcherImpl[IO](
          mockPeerChannel,
          unusedReceiptStorage,
          maxReceiptsPerRequest
        )

        val allBlockHashes = receiptsMap.keys.toList
        val test = for {
          result <- receiptsFetcher.fetchReceipts(allBlockHashes, NonEmptyList.of(peerWithWithInfo.id))
        } yield result match {
          case Left(ReceiptsFetcher.ReceiptsFetchError.InvalidReceipts(_, _)) => succeed
          case Left(error)                                                    => fail(show"Unexpected error: $error")
          case Right(_)                                                       => fail("Expected InvalidReceipts error")
        }

        test.ioValue
      }
    }
  }

  private def unusedReceiptStorage: ReceiptStorage[IO] =
    new ReceiptStorage[IO] {
      override def getReceiptsByHash(hash: BlockHash): IO[Option[Seq[Receipt]]] =
        IO.raiseError(new RuntimeException("Should not be called"))

      override def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): IO[Unit] =
        IO.raiseError(new RuntimeException("Should not be called"))
    }

  private def createMockChannel(
      receiptsMap: Map[BlockHash, Seq[Receipt]],
      maxReceiptsPerRequest: Int
  ): PeerChannel[IO] =
    new PeerChannel[IO] {
      override def askPeers[Response <: OBFT1.ResponseMessage: ClassTag](
          peerIds: NonEmptyList[PeerId],
          requestMessage: OBFT1.RequestMessage
      ): IO[Either[PeerChannel.MultiplePeersAskFailure, Response]] = requestMessage match {
        case OBFT1.GetReceipts(requestId, blockHashes) =>
          blockHashes.toList.traverse(receiptsMap.get) match {
            case Some(receipts) =>
              if (blockHashes.size > maxReceiptsPerRequest)
                IO.raiseError(
                  new RuntimeException(
                    s"Requested ${receipts.size} receipts which is more than the limit $maxReceiptsPerRequest"
                  )
                )
              else {
                IO.pure(
                  Right[PeerChannel.MultiplePeersAskFailure, Response](
                    OBFT1.Receipts(requestId, receipts).asInstanceOf[Response]
                  )
                )
              }
            case None =>
              IO.raiseError(new RuntimeException("Receipts not found"))
          }
        case _ =>
          IO.raiseError(new RuntimeException("Unexpected request message"))
      }
    }

}
