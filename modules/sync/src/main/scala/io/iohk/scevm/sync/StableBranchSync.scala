package io.iohk.scevm.sync

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.sync.StableBranchSync.StableBranchSyncResult

trait StableBranchSync[F[_]] {
  def catchup(): F[StableBranchSyncResult]
}

object StableBranchSync {

  sealed trait StableBranchSyncResult extends Product with Serializable
  sealed trait StableBranchSyncError  extends StableBranchSyncResult

  object StableBranchSyncResult {
    final case class Success(from: ObftHeader, to: ObftHeader) extends StableBranchSyncResult

    final case class AheadOfNetworkStable(localStableHeader: ObftHeader, networkStableHeader: ObftHeader)
        extends StableBranchSyncError
    final case class CantConnectToNodeStable(localStableHeader: ObftHeader, networkStableHeader: ObftHeader)
        extends StableBranchSyncError
    final case class ValidationError(message: String)    extends StableBranchSyncError
    final case class BranchFetcherError(message: String) extends StableBranchSyncError
    final case class StorageError(message: String)       extends StableBranchSyncError
    final case object NetworkStableNotFound              extends StableBranchSyncError
    final case object NoAvailablePeer                    extends StableBranchSyncError
    final case object NetworkInitialization              extends StableBranchSyncError

    implicit val show: Show[StableBranchSyncResult] = Show.show[StableBranchSyncResult] {
      case Success(from, to) =>
        show"Stable branch sync successful from $from to $to"
      case AheadOfNetworkStable(localStableHeader, networkStableHeader) =>
        show"Local node stable ($localStableHeader is ahead of network stable $networkStableHeader"
      case CantConnectToNodeStable(localStableHeader, networkStableHeader) =>
        show"Local node stable ($localStableHeader is not connected to network stable $networkStableHeader"
      case ValidationError(message) =>
        show"Validation error due to $message"
      case BranchFetcherError(message) =>
        show"Error while fetching a branch due to $message"
      case StorageError(message) =>
        show"Storage error due to $message"
      case NetworkStableNotFound =>
        "Unable to find a network stable header"
      case NoAvailablePeer =>
        "No peer available"
      case NetworkInitialization =>
        "Clean chain detected, all connected peers are at genesis"
    }
  }

  def apply[F[_]](implicit stableBranchSync: StableBranchSync[F]): StableBranchSync[F] = stableBranchSync
}
