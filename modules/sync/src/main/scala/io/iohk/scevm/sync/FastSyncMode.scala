package io.iohk.scevm.sync

import cats.data.NonEmptyList
import cats.effect.{Ref, Sync}
import cats.syntax.all._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.{BranchFetcher, MptNodeFetcher, PeerId}
import io.iohk.scevm.sync.FastSyncMode.FastSyncPhase
import io.iohk.scevm.sync.SyncMode.{FastSyncFailure, SyncValidationError}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Fast synchronization algorithm: https://github.com/agiletechvn/go-ethereum-code-analysis/blob/master/fast-sync-algorithm.md
  *
  * Start in `DownloadMptNodes` phase until all MPT nodes are downloaded, then switch to `FullSyncCatchup` to execute all blocks
  * that have been minted after the pivot block.
  */
class FastSyncMode[F[_]: Sync] private (
    branchWithReceiptsFetcher: BranchFetcher[F],
    mptNodesFetcher: MptNodeFetcher[F],
    fullSyncMode: FullSyncMode[F],
    phase: Ref[F, FastSyncPhase]
) extends SyncMode[F] {

  override def fetchBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[BranchFetcher.BranchFetcherResult] = phase.get.flatMap {
    case FastSyncPhase.InitialSync =>
      /** Download all blocks and receipts */
      branchWithReceiptsFetcher.fetchBranch(from, to, peerIds)
    case FastSyncPhase.FullSyncCatchup =>
      /** Delegate to [[FullSyncMode]] to fetch the branch. Receipts are not downloaded. */
      fullSyncMode.fetchBranch(from, to, peerIds)
  }

  override def validateBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[Either[SyncValidationError, Unit]] =
    phase.get.flatMap {
      case FastSyncPhase.InitialSync =>
        log.info("Start downloading MPT nodes") >>
          mptNodesFetcher.fetch(List(to.stateRoot), peerIds).flatMap {
            case Left(e) =>
              val message = s"Failed to download MPT nodes for block ${to.number} with stateRoot ${Hex
                .toHexString(to.stateRoot)}, reason: ${e.toString}"
              log
                .error(message)
                .as(Either.left[SyncValidationError, Unit](FastSyncFailure(message)))
            case Right(_) =>
              (log.info("MPT nodes have been downloaded, switching to full-sync phase") >>
                phase.set(FastSyncPhase.FullSyncCatchup))
                .as(().asRight)

          }
      case FastSyncPhase.FullSyncCatchup =>
        fullSyncMode.validateBranch(from, to, peerIds)
    }

  private val log: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
}

object FastSyncMode {
  def apply[F[_]: Sync](
      branchFetcher: BranchFetcher[F],
      mptNodesFetcher: MptNodeFetcher[F],
      fullSyncMode: FullSyncMode[F]
  ): F[FastSyncMode[F]] =
    for {
      phase <- Ref.of[F, FastSyncPhase](FastSyncPhase.InitialSync)
    } yield new FastSyncMode(branchFetcher, mptNodesFetcher, fullSyncMode, phase)

  sealed trait FastSyncPhase

  object FastSyncPhase {

    /*
     * Initial synchronization phase:
     * 1. Download blocks *and* receipts
     * 2. Download all MPT blocks at the pivot block
     */
    case object InitialSync extends FastSyncPhase

    /** Emulate FullSyncMode after the initial sync is done.
      */
    case object FullSyncCatchup extends FastSyncPhase
  }
}
