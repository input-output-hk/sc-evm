package io.iohk.scevm.network

import cats.data.NonEmptyList
import io.iohk.scevm.consensus.validators.HeaderValidator.HeaderError
import io.iohk.scevm.domain.{BlockHash, ObftHeader}
import io.iohk.scevm.network.BlocksFetcher.BlocksFetchError

/** BlockFetcher is responsible for fetching a single 'segment' of blocks from the network.
  * It is used by the [[BranchFetcher]] as a single step in the fetching process.
  */
trait BlocksFetcher[F[_]] {

  /** Fetches the blocks from the given block hash.
    *
    * @param target block to start from
    * @param numBlocks number of blocks to request
    * @param peerIds peers to download from
    * @return header of the last downloaded block
    */
  def fetchBlocks(
      target: BlockHash,
      numBlocks: Int,
      peerIds: NonEmptyList[PeerId]
  ): F[Either[BlocksFetchError, ObftHeader]]
}

object BlocksFetcher {
  sealed trait BlocksFilterResult
  object BlocksFilterResult {
    final case class NotDownloaded(adjustedTarget: BlockHash, adjustedNumBlocks: Int) extends BlocksFilterResult
    final case class FullyDownloaded(lastBlockHeader: ObftHeader)                     extends BlocksFilterResult
  }

  sealed trait BlocksFetchError
  object BlocksFetchError {
    final case object AllPeersTimedOut                                  extends BlocksFetchError
    final case object EmptyBlocksList                                   extends BlocksFetchError
    final case class InvalidHeaders(headers: NonEmptyList[HeaderError]) extends BlocksFetchError
  }
}
