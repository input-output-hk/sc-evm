package io.iohk.scevm.sync

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all._
import io.iohk.scevm.consensus.pos.BranchExecution
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.{BranchFetcher, PeerId}
import io.iohk.scevm.sync.SyncMode.{EvmValidationByExecutionError, SyncValidationError}
import org.typelevel.log4cats.LoggerFactory

class FullSyncMode[F[_]: Monad: LoggerFactory](
    branchFetcher: BranchFetcher[F],
    branchExecution: BranchExecution[F]
) extends SyncMode[F] {

  /** Just delegate to [[BranchFetcher]] to fetch a branch. Receipts are not downloaded.
    */
  override def fetchBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[BranchFetcher.BranchFetcherResult] = branchFetcher.fetchBranch(from, to, peerIds)

  /** Execute the range by fetching it from the storage and pass it to the execution service
    */
  override def validateBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[Either[SyncValidationError, Unit]] =
    branchExecution
      .executeRange(from, to)
      .flatTap {
        case Left(_) => Monad[F].unit
        case Right(_) =>
          val numBlocksExecuted = to.number.distance(from.number).value
          log.debug(show"$numBlocksExecuted blocks executed from $from to $to")
      }
      .map(_.leftMap(error => EvmValidationByExecutionError(show"$error")))

  private val log = LoggerFactory[F].getLoggerFromClass(this.getClass)
}
