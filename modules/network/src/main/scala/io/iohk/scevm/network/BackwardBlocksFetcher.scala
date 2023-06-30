package io.iohk.scevm.network

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import io.iohk.scevm.consensus.metrics.TimeToFinalizationTracker
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.consensus.validators.HeaderValidator.HeaderError
import io.iohk.scevm.db.storage.{BlocksReader, BlocksWriter}
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftHeader}
import io.iohk.scevm.network.BlocksFetcher.BlocksFetchError.InvalidHeaders
import io.iohk.scevm.network.PeerChannel.MultiplePeersAskFailure
import io.iohk.scevm.network.p2p.messages.OBFT1
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** `BackwardBlocksFetcher` fetches blocks backwards and writes them to the database. Before requesting blocks,
  * it checks if the database already contains the requested blocks. It also checks if the block headers are valid.
  */
final case class BackwardBlocksFetcher[F[_]: Async](
    peerChannel: PeerChannel[F],
    blocksWriter: BlocksWriter[F],
    blockReader: BlocksReader[F],
    headerValidator: HeaderValidator[F],
    timeToFinalizationTracker: TimeToFinalizationTracker[F]
) extends BlocksFetcher[F] {
  import BlocksFetcher._

  val log: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  /** Checks the storage for the already downloaded blocks before requesting them from the peers.
    */
  def fetchBlocks(
      target: BlockHash,
      numBlocks: Int,
      peerIds: NonEmptyList[PeerId]
  ): F[Either[BlocksFetchError, ObftHeader]] =
    for {
      filteringResult <- filterDownloadedBlocks(target, numBlocks)
      result <- filteringResult match {
                  case BlocksFilterResult.NotDownloaded(adjustedTarget, adjustedNumBlocks) =>
                    askPeersForBlocks(peerIds, adjustedTarget, adjustedNumBlocks).flatMap {
                      case Right(blocks @ _ :+ last) =>
                        for {
                          _ <- log.info(show"Received ${blocks.length} blocks, the oldest is $last")
                          invalidBlocks <- blocks
                                             .traverse { block =>
                                               headerValidator.validate(block.header).flatMap {
                                                 case Right(_) =>
                                                   timeToFinalizationTracker.track(block.hash) >>
                                                     blocksWriter
                                                       .insertBlock(block)
                                                       .as(List[HeaderError]())
                                                 case Left(e) => List(e).pure[F]
                                               }
                                             }
                                             .map(_.flatten.toList)
                        } yield invalidBlocks match {
                          case err :: errors =>
                            Left(InvalidHeaders(NonEmptyList(err, errors))): Either[BlocksFetchError, ObftHeader]
                          case Nil => Right(last.header): Either[BlocksFetchError, ObftHeader]
                        }
                      case Right(_) =>
                        log
                          .error(
                            show"Received empty list of blocks when asking for $adjustedNumBlocks ancestors from block $adjustedTarget."
                          )
                          .as[Either[BlocksFetchError, ObftHeader]](Left(BlocksFetchError.EmptyBlocksList))
                      case Left(MultiplePeersAskFailure.AllPeersTimedOut) =>
                        log
                          .error(
                            s"All peers timed out, cannot download blocks from $target, timed out peers: $peerIds"
                          )
                          .as[Either[BlocksFetchError, ObftHeader]](Left(BlocksFetchError.AllPeersTimedOut))
                    }
                  case BlocksFilterResult.FullyDownloaded(last) =>
                    log.info(
                      show"The ${numBlocks} blocks to block $last have already been downloaded, skipping"
                    ) >>
                      Applicative[F].pure[Either[BlocksFetchError, ObftHeader]](Right(last))
                }
    } yield result

  /** Ask peers for blocks, return the list of blocks or an empty list if no blocks were received
    * Retries are handled by PeerChannel.
    */
  private def askPeersForBlocks(
      peerIds: NonEmptyList[PeerId],
      targetHash: BlockHash,
      count: Int
  ): F[Either[MultiplePeersAskFailure, Seq[ObftBlock]]] =
    log.info(show"Requesting $count full blocks starting at $targetHash from ${peerIds.length} peers") >>
      log.debug(show"Requesting $count full blocks from peers $peerIds ") >>
      peerChannel.askPeers[OBFT1.FullBlocks](peerIds, OBFT1.GetFullBlocks(targetHash, count)).map(_.map(_.blocks))

  /** Filters the blocks that have already been downloaded. Tries to fetch `numberOfBlocks` headers from the storage
    * in reverse order starting from `targetHash`, stops as soon as the first header is not found.
    */
  private def filterDownloadedBlocks(targetHash: BlockHash, numberOfBlocks: Int): F[BlocksFilterResult] =
    (targetHash, numberOfBlocks).tailRecM { case (nextBlockHash, numBlocks) =>
      blockReader.getBlockHeader(nextBlockHash).map {
        case Some(downloadedBlockHeader) if numBlocks == 1 =>
          Right(BlocksFilterResult.FullyDownloaded(downloadedBlockHeader))
        case Some(downloadedBlockHeader) =>
          Left((downloadedBlockHeader.parentHash, numBlocks - 1))
        case None =>
          Right(BlocksFilterResult.NotDownloaded(nextBlockHash, numBlocks))
      }
    }
}
