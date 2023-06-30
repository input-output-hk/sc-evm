package io.iohk.scevm.consensus.domain

import cats.data.{EitherT, NonEmptyList, OptionT}
import cats.implicits._
import cats.{Monad, MonadThrow, Show}
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftHeader}
import io.iohk.scevm.ledger.BlockProvider
import org.typelevel.log4cats.LoggerFactory

import ForwardBranchSlice.ForwardBranchSliceError._
import ForwardBranchSlice.{ForwardBranchSliceError, Range}

/** Provide a stream of header for a consecutive range [from; to], in ascending order and including boundaries
  */
trait ForwardBranchSlice[F[_]] {
  def range: Range
  def headerStream: fs2.Stream[F, ObftHeader]
  def blockStream: fs2.Stream[F, ObftBlock]
}
object ForwardBranchSlice {

  final case class Range(from: BlockHash, to: BlockHash)

  sealed trait ForwardBranchSliceError
  object ForwardBranchSliceError {
    final case class DisconnectedBranch(expectedFrom: ObftHeader, actualFrom: ObftHeader, to: ObftHeader)
        extends ForwardBranchSliceError
    final case class EmptyBranch(from: BlockHash, to: BlockHash) extends ForwardBranchSliceError
    final case class MissingHeader(blockHash: BlockHash)         extends ForwardBranchSliceError
    final case class MissingBody(blockHash: BlockHash)           extends ForwardBranchSliceError
    final case class InvalidBoundariesOrder(fromHeader: ObftHeader, toHeader: ObftHeader)
        extends ForwardBranchSliceError

    implicit val errorShow: Show[ForwardBranchSliceError] = Show.show[ForwardBranchSliceError] {
      case DisconnectedBranch(expectedFrom, actualFrom, to) =>
        show"disconnected branch between $expectedFrom and $to, forking at $actualFrom"
      case EmptyBranch(from, to)    => show"branch is empty between hashes $from and $to"
      case MissingHeader(blockHash) => show"unable to load header for hash $blockHash"
      case MissingBody(blockHash)   => show"unable to load body for hash $blockHash"
      case InvalidBoundariesOrder(fromHeader, toHeader) =>
        show"invalid order, $fromHeader should be before $toHeader"
    }
  }

  /** Return an instance of ForwardBranchSlice between the two hashes (inclusive).
    * The implementation is dependant on the range size and the config.maxHeadersCount field
    */
  def build[F[_]: MonadThrow: LoggerFactory](
      maxHeadersInMemoryCount: Int,
      blockProvider: BlockProvider[F],
      fromHash: BlockHash,
      toHash: BlockHash
  ): F[Either[ForwardBranchSliceError, ForwardBranchSlice[F]]] = {
    val ret = for {
      fromHeader <- EitherT.fromOptionF(blockProvider.getHeader(fromHash), MissingHeader(fromHash))
      toHeader   <- EitherT.fromOptionF(blockProvider.getHeader(toHash), MissingHeader(toHash))
      slice      <- EitherT(build(maxHeadersInMemoryCount, blockProvider, fromHeader, toHeader))
    } yield slice

    ret.value
  }

  /** Return an instance of ForwardBranchSlice between the two headers (inclusive).
    * The implementation is dependant on the range size and hte maxHeadersCount parameter
    */
  private[domain] def build[F[_]: MonadThrow: LoggerFactory](
      maxHeadersInMemoryCount: Int,
      blockProvider: BlockProvider[F],
      fromHeader: ObftHeader,
      toHeader: ObftHeader
  ): F[Either[ForwardBranchSliceError, ForwardBranchSlice[F]]] = {
    val log = LoggerFactory[F].getLoggerFromClass(this.getClass)
    if (toHeader.number < fromHeader.number) {
      val error = InvalidBoundariesOrder(fromHeader, toHeader)
      log
        .trace(
          show"Unable to build a ForwardBranchSlice between $fromHeader and $toHeader due to: $error}"
        )
        .as(Either.left[ForwardBranchSliceError, ForwardBranchSlice[F]](error))
    } else if (toHeader.number.value - fromHeader.number.value + 1 <= maxHeadersInMemoryCount) {
      log.trace(
        show"Building a MemoryForwardBranchSlice, range from $fromHeader to $toHeader is smaller or equals to $maxHeadersInMemoryCount"
      ) *>
        HeadersInMemoryForwardBranchSlice.build(blockProvider, fromHeader, toHeader)
    } else {
      log.trace(
        show"Building a StorageForwardBranchSlice, range from $fromHeader to $toHeader is larger than $maxHeadersInMemoryCount"
      ) *>
        StorageForwardBranchSlice.build(maxHeadersInMemoryCount, blockProvider, fromHeader, toHeader)
    }
  }

  private[domain] def loadHeaders[F[_]: Monad](
      blockProvider: BlockProvider[F],
      fromHeader: ObftHeader,
      toHeader: ObftHeader
  ): F[Either[ForwardBranchSliceError, List[ObftHeader]]] =
    loadSparseHeaders(blockProvider, 0, fromHeader, toHeader)

  private[domain] def loadSparseHeaders[F[_]: Monad](
      blockProvider: BlockProvider[F],
      elementsToSkip: BigInt,
      fromHeader: ObftHeader,
      toHeader: ObftHeader
  ): F[Either[ForwardBranchSliceError, List[ObftHeader]]] =
    (toHeader.hash, BigInt(0), List.empty[ObftHeader]).tailRecM { case (nextHeaderHash, skipCount, acc) =>
      blockProvider.getHeader(nextHeaderHash).map {
        case Some(header) if header.number > fromHeader.number && skipCount != 0 =>
          Left((header.parentHash, skipCount - 1, acc))
        case Some(header) if header.number > fromHeader.number && skipCount == 0 =>
          Left((header.parentHash, elementsToSkip, header +: acc))
        case Some(header) if header == fromHeader => Right(Right(header +: acc))
        case Some(disconnectedHeader) =>
          Right(Left(DisconnectedBranch(fromHeader, disconnectedHeader, toHeader))) // disconnected
        case None => Right(Left(MissingHeader(nextHeaderHash))) // storage error
      }
    }
}

/** A ForwardBranchSlice implementation where all headers are consecutive and
  * stored in memory.
  * The BlockProvider is still used for bodies retrieval.
  */
private[domain] class HeadersInMemoryForwardBranchSlice[F[_]: MonadThrow](
    blockProvider: BlockProvider[F],
    val range: Range,
    headers: NonEmptyList[ObftHeader]
) extends ForwardBranchSlice[F] {
  override def headerStream: fs2.Stream[F, ObftHeader] = fs2.Stream.emits(headers.toList)

  override def blockStream: fs2.Stream[F, ObftBlock] =
    headerStream
      .evalMap { header =>
        OptionT(blockProvider.getBody(header.hash))
          .map(ObftBlock(header, _))
          .getOrRaise[Throwable](new RuntimeException(show"${MissingBody(header.hash)}"))
      }
}

private object HeadersInMemoryForwardBranchSlice {

  def build[F[_]: MonadThrow](
      blockProvider: BlockProvider[F],
      fromHeader: ObftHeader,
      toHeader: ObftHeader
  ): F[Either[ForwardBranchSliceError, ForwardBranchSlice[F]]] =
    ForwardBranchSlice
      .loadHeaders(blockProvider, fromHeader, toHeader)
      .map(result =>
        result.flatMap {
          case head :: tail =>
            Right(
              new HeadersInMemoryForwardBranchSlice[F](
                blockProvider,
                Range(fromHeader.hash, toHeader.hash),
                NonEmptyList(head, tail)
              )
            )
          case Nil => Left(EmptyBranch(fromHeader.hash, toHeader.hash))
        }
      )

}

/** A ForwardBranchSlice implementation where:
  * - the passed headers are one-every-x header
  * - to build the whole stream, it recursively looks up the missing headers sub-slices
  * - the bodies are always retrieved from storage
  */
private class StorageForwardBranchSlice[F[_]: MonadThrow: LoggerFactory](
    maxHeadersInMemoryCount: Int,
    blockProvider: BlockProvider[F],
    val range: Range,
    sparseHeaders: Seq[ObftHeader]
) extends ForwardBranchSlice[F] {

  override def headerStream: fs2.Stream[F, ObftHeader] =
    fs2.Stream.emit(sparseHeaders.head) ++
      fs2.Stream
        .emits(sparseHeaders.zip(sparseHeaders.tail))
        .evalMap { case (fromHeader, toHeader) =>
          ForwardBranchSlice
            .build(maxHeadersInMemoryCount, blockProvider, fromHeader, toHeader)
            .map(
              _.fold(
                e => throw new RuntimeException(ForwardBranchSliceError.errorShow.show(e)),
                subslice => subslice.headerStream.drop(1)
              )
            )
        }
        .flatten

  override def blockStream: fs2.Stream[F, ObftBlock] =
    headerStream
      .evalMap { header =>
        blockProvider
          .getBody(header.hash)
          .map(
            _.map(ObftBlock(header, _))
              .getOrElse(throw new RuntimeException(show"${MissingBody(header.hash)}"))
          )
      }
}

private object StorageForwardBranchSlice {

  /** Build a ForwardBranchSlice backed up by storage (ie, each block header retrieved will trigger at least
    * a storage access).
    *
    * @param blockProvider where to read block headers from
    * @param targetHeadersCount the target block headers count that we want to keep in memory.
    *                           This limit will not be strictly respected, as rounding error
    *                           could slightly shift the final headers count in memory.
    * @param fromHeader the starting header (ascending, inclusive)
    * @param toHeader the ending header (ascending, inclusive
    */
  def build[F[_]: MonadThrow: LoggerFactory](
      maxHeadersInMemoryCount: Int,
      blockProvider: BlockProvider[F],
      fromHeader: ObftHeader,
      toHeader: ObftHeader
  ): F[Either[ForwardBranchSliceError, ForwardBranchSlice[F]]] = {
    // Please note that this skip value is a gross approximation. Proper computation should
    // take into account the integer rounding, and eventually apply a different skip value
    // while walking the slice to not end up with a disproportionate last range.
    // This simpler version is easier to read, and good enough considering that the storage
    // version is expected to be used on long blocks slices.
    // As such, a delta error of 1 or 2 is irrelevant.
    val skip = toHeader.number.distance(fromHeader.number).value / maxHeadersInMemoryCount
    (for {
      sparseHeaders <- EitherT(ForwardBranchSlice.loadSparseHeaders(blockProvider, skip, fromHeader, toHeader))
    } yield new StorageForwardBranchSlice[F](
      maxHeadersInMemoryCount,
      blockProvider,
      Range(fromHeader.hash, toHeader.hash),
      sparseHeaders
    )).widen[ForwardBranchSlice[F]].value
  }
}
