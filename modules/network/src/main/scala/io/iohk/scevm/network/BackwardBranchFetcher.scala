package io.iohk.scevm.network

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import io.iohk.scevm.domain.{BlockHash, ObftHeader}
import io.iohk.scevm.metrics.instruments.Counter

final case class BackwardBranchFetcher[F[_]: Async](
    blocksFetcher: BlocksFetcher[F],
    branchFetcherConfig: BranchFetcherConfig,
    fetchedBlocksCounter: Counter[F]
) extends BranchFetcher[F] {
  import BranchFetcher._

  /** Fetches a branch or fails. After downloading the branch [from, to], it checks if the last downloaded block (closer to genesis)
    * matches `from` header and returns the appropriate `BranchFetchResult`.
    *
    * @param from The header with the lowest block number (fetched last)
    * @param to Target header with the highest block number to start from (fetched first)
    * @param peerIds The list of peers to fetch the branch from
    * @return `BranchFetcherResult`
    */
  // scalastyle:off method.length
  def fetchBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[BranchFetcherResult] = {

    /** Splits the download of a long branch into multiple smaller fetches capped by the `maxBlocksBodiesPerMessage`.
      * Blocks are stored in the database as they are received. Stops early as soon as one of the fetches fails.
      */
    def fetchBlocksRecursively(
        remainingBlocksCount: Int,
        targetBlockHash: BlockHash,
        currentSegmentHeader: ObftHeader
    ): F[BranchFetcherResult] =
      if (remainingBlocksCount <= 0)
        if (currentSegmentHeader.hash == from.hash)
          Applicative[F].pure(BranchFetcherResult.Connected(from, to))
        else
          Applicative[F].pure(BranchFetcherResult.Disconnected(from, currentSegmentHeader, to))
      else {
        val numBlocksToRequest =
          if (remainingBlocksCount > branchFetcherConfig.maxBlocksPerMessage)
            branchFetcherConfig.maxBlocksPerMessage
          else
            remainingBlocksCount

        for {
          downloadResult <- blocksFetcher.fetchBlocks(targetBlockHash, numBlocksToRequest, peerIds)
          result <- downloadResult match {
                      case Right(lastDownloadedBlockHeader) =>
                        fetchedBlocksCounter.inc(numBlocksToRequest) >>
                          fetchBlocksRecursively(
                            remainingBlocksCount - numBlocksToRequest,
                            lastDownloadedBlockHeader.parentHash,
                            lastDownloadedBlockHeader
                          )
                      case Left(BlocksFetcher.BlocksFetchError.EmptyBlocksList) =>
                        BranchFetcherResult.RecoverableError("Downloaded empty blocks list").pure
                      case Left(BlocksFetcher.BlocksFetchError.AllPeersTimedOut) =>
                        BranchFetcherResult.RecoverableError("All peers timed out").pure
                      case Left(BlocksFetcher.BlocksFetchError.InvalidHeaders(headers)) =>
                        BranchFetcherResult
                          .RecoverableError(
                            show"Received invalid headers: ${headers.map(_.header)}"
                          )
                          .pure
                    }
        } yield result
      }

    if (from.number > to.number)
      Applicative[F].pure(BranchFetcherResult.InvalidRange)
    else {
      val numRequestedBlocks = to.number.distance(from.number).value.toInt + 1
      fetchBlocksRecursively(numRequestedBlocks, to.hash, to)
    }
  }
  // scalastyle:on method.length
}
