package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Monad, Show}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.db.storage.ReceiptStorage
import io.iohk.scevm.domain.{ObftHeader, Receipt}
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.network.PeerChannel.MultiplePeersAskFailure
import io.iohk.scevm.network.ReceiptsFetcher.ReceiptsFetchError
import io.iohk.scevm.network.p2p.messages.OBFT1
import org.typelevel.log4cats.LoggerFactory

/** Downloads and stores the receipts for a given list of block hashes.
  * During the 'fast synchronization' the blocks are not executed, so the receipts need to be fetched from the network.
  */
trait ReceiptsFetcher[F[_]] {
  def fetchReceipts(
      headers: Seq[ObftHeader],
      peerIds: NonEmptyList[PeerId]
  ): F[Either[ReceiptsFetchError, Unit]]
}

object ReceiptsFetcher {
  sealed trait ReceiptsFetchError
  object ReceiptsFetchError {
    final case object AllPeersTimedOut extends ReceiptsFetchError
    final case object InvalidResponse  extends ReceiptsFetchError
    final case class InvalidReceipts(expectedRootHash: ByteString, actualRootHash: ByteString)
        extends ReceiptsFetchError

    def userFriendlyMessage(error: ReceiptsFetchError): String = error match {
      case AllPeersTimedOut => "All peers timed out."
      case InvalidResponse  => "Received an invalid response from peers."
      case InvalidReceipts(expectedRootHash, actualRootHash) =>
        s"The received receipt do not match the expected hash (expected: ${Hex.toHexString(expectedRootHash)}, " +
          s"actual: ${Hex.toHexString(actualRootHash)}"
    }

    implicit val receiptsFetchErrorShow: Show[ReceiptsFetchError] = cats.derived.semiauto.show

    object InvalidReceipts {
      implicit val invalidReceiptsShow: Show[InvalidReceipts] = Show.show(e =>
        s"InvalidReceipts(expected = ${Hex.toHexString(e.expectedRootHash)}, actual = ${Hex.toHexString(e.actualRootHash)})"
      )
    }
  }
}

final class ReceiptsFetcherImpl[F[_]: Monad: LoggerFactory](
    peerChannel: PeerChannel[F],
    receiptStorage: ReceiptStorage[F],
    maxReceiptsPerRequest: Int
) extends ReceiptsFetcher[F] {

  override def fetchReceipts(
      headers: Seq[ObftHeader],
      peerIds: NonEmptyList[PeerId]
  ): F[Either[ReceiptsFetchError, Unit]] =
    headers
      .grouped(maxReceiptsPerRequest)
      .toList
      .traverse { hashes =>
        fetchReceiptsUnbounded(hashes, peerIds)
      }
      .map {
        case Nil        => Right(())
        case error :: _ => error
      }

  private def fetchReceiptsUnbounded(
      headers: Seq[ObftHeader],
      peerIds: NonEmptyList[PeerId]
  ): F[Either[ReceiptsFetchError, Unit]] =
    log.info(show"Asking peers for ${headers.length} receipts starting from: ${headers.headOption.mkString}") >>
      peerChannel.askPeers[OBFT1.Receipts](peerIds, OBFT1.GetReceipts(headers.map(_.hash))).flatMap {
        case Right(OBFT1.Receipts(_, receipts)) =>
          if (headers.length != receipts.length)
            log
              .warn(
                s"Received receipts list of different length than requested. Requested: ${headers.length}, received: ${receipts.length}"
              )
              .as(
                Either.left[ReceiptsFetchError, Unit](ReceiptsFetchError.InvalidResponse)
              )
          else {
            log.info(s"Received ${receipts.length} receipts") >>
              headers
                .zip(receipts)
                .traverse { case (header, receipts) =>
                  val receiptsRoot = MptStorage.rootHash(receipts, Receipt.byteArraySerializable)
                  if (receiptsRoot == header.receiptsRoot)
                    receiptStorage.putReceipts(header.hash, receipts).as(Either.right[ReceiptsFetchError, Unit](()))
                  else {
                    val error = ReceiptsFetchError.InvalidReceipts(header.receiptsRoot, receiptsRoot)
                    log
                      .warn(
                        show"Received receipts for header $header with invalid receipts root: $error"
                      )
                      .as(Either.left[ReceiptsFetchError, Unit](error))
                  }
                }
                .map(results => results.sequence.map(_ => ()))
          }
        case Left(MultiplePeersAskFailure.AllPeersTimedOut) =>
          log
            .error(
              show"All peers timed out. Cannot download receipts. Timed out peers: ${peerIds.toList.mkString(", ")}"
            )
            .as(Either.left[ReceiptsFetchError, Unit](ReceiptsFetchError.AllPeersTimedOut))
      }

  private val log = LoggerFactory[F].getLoggerFromClass(this.getClass)
}
