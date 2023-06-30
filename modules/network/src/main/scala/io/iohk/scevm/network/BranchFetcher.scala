package io.iohk.scevm.network

import cats.Show
import cats.data.NonEmptyList
import cats.implicits.showInterpolator
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.BranchFetcher.BranchFetcherResult

/** A branch fetcher is responsible for fetching a full branch from the network. Downloaded blocks are validated
  * and stored in the database.
  */
trait BranchFetcher[F[_]] {

  /** Fetches a full branch from the network.
    * @param from The block hash of the first block in the branch (ancestor)
    * @param to The block hash of the last block in the branch (tip)
    * @param peerIds The list of peers to fetch the branch from.
    * @return [[BranchFetcherResult]]
    */
  def fetchBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[BranchFetcherResult]
}

object BranchFetcher {
  sealed trait BranchFetcherResult
  object BranchFetcherResult {
    final case class Connected(from: ObftHeader, to: ObftHeader) extends BranchFetcherResult
    final case class Disconnected(expectedFrom: ObftHeader, actualFrom: ObftHeader, to: ObftHeader)
        extends BranchFetcherResult
    final case object InvalidRange                     extends BranchFetcherResult
    final case class RecoverableError(message: String) extends BranchFetcherResult
    final case class FatalError(message: String)       extends BranchFetcherResult

    def userFriendlyMessage(result: BranchFetcherResult): String = result match {
      case Connected(from, to)               => show"The branch [$from, $to] is connected."
      case Disconnected(expectedFrom, _, to) => show"The block $to is not connected to $expectedFrom."
      case InvalidRange                      => "The provided range is invalid."
      case RecoverableError(message)         => show"Recoverable error: $message."
      case FatalError(message)               => show"Fatal error: $message."
    }

    implicit val show: Show[BranchFetcherResult] = cats.derived.semiauto.show
  }
}
