package io.iohk.scevm.sync

import cats.data.NonEmptyList
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.BranchFetcher.BranchFetcherResult
import io.iohk.scevm.network.PeerId

trait SyncMode[F[_]] {
  def fetchBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[BranchFetcherResult]

  def validateBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[Either[SyncMode.SyncValidationError, Unit]]
}

object SyncMode {
  sealed trait SyncValidationError
  final case class EvmValidationByExecutionError(reason: String) extends SyncValidationError
  final case class FastSyncFailure(reason: String)               extends SyncValidationError
}
