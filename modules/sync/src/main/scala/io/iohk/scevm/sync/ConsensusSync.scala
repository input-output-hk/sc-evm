package io.iohk.scevm.sync

import cats.data.NonEmptyList
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.{Async, Fiber, Sync}
import cats.syntax.all._
import fs2.concurrent.Signal
import io.iohk.scevm.db.storage.BranchProvider.MissingParent
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.BranchFetcher.BranchFetcherResult
import io.iohk.scevm.network.{BranchFetcher, PeerId}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** ConsensusSync is responsible for downloading missing blocks/branches. It is triggered after running
  * the consensus on a new block received from the network and the parent of that block is locally unknown.
  *
  * More info:
  *  https://input-output.atlassian.net/wiki/spaces/EMU/pages/3595665553/Seeker+in+consensus#%E2%80%9CConsensusSync%E2%80%9D-(Seeker-replacement)
  */
trait ConsensusSync[F[_]] {
  def onMissingParent(currentStableHeader: ObftHeader, missingParent: MissingParent): F[Unit]
}

class ConsensusSyncImpl[F[_]] private (
    requestsQueue: Queue[F, (ObftHeader, MissingParent)],
    fiber: Fiber[F, Throwable, Unit]
) extends ConsensusSync[F] {

  /** Add a new branch [stable; missingBlock] to the queue of blocks to be downloaded.
    * The function returns immediately and the missing branch is downloaded asynchronously.
    *
    * If the provided range doesn't form a valid branch the function will log an error but keep the
    * downloaded blocks in the storage.
    */
  override def onMissingParent(currentStableHeader: ObftHeader, missingParent: MissingParent): F[Unit] =
    requestsQueue.offer((currentStableHeader, missingParent))

  private def cancel: F[Unit] =
    fiber.cancel
}

object ConsensusSyncImpl {

  def apply[F[_]: Async](
      branchFetcher: BranchFetcher[F],
      peersRef: Signal[F, List[PeerId]]
  ): Resource[F, ConsensusSyncImpl[F]] = {
    import cats.effect.implicits.genSpawnOps
    val consensusSyncF = for {
      queue     <- Queue.unbounded[F, (ObftHeader, MissingParent)]
      downloader = MissingBlocksDownloader[F](branchFetcher, peersRef)
      fiber     <- handleRequests(downloader, queue).start
    } yield new ConsensusSyncImpl[F](queue, fiber)

    Resource.make(consensusSyncF)(_.cancel)
  }

  private def handleRequests[F[_]: Sync](
      downloader: MissingBlocksDownloader[F],
      requestsQueue: Queue[F, (ObftHeader, MissingParent)]
  ): F[Unit] =
    requestsQueue.take.flatMap(downloader.downloadMissingParents) >>
      handleRequests(downloader, requestsQueue)

  final private case class MissingBlocksDownloader[F[_]: Sync](
      branchFetcher: BranchFetcher[F],
      peersRef: Signal[F, List[PeerId]]
  ) {
    private val logger = Slf4jLogger.getLogger

    def downloadMissingParents(missingBlockRequest: (ObftHeader, MissingParent)): F[Unit] = {
      val (currentStable, missingParent) = missingBlockRequest
      peersRef.get.flatMap {
        case peer :: peers =>
          for {
            branchFetcherResult <-
              branchFetcher.fetchBranch(currentStable, missingParent.orphanBlockHeader, NonEmptyList(peer, peers))
            _ <- branchFetcherResult match {
                   case BranchFetcherResult.Connected(from, to) =>
                     logger.info(s"Successfully downloaded missing blocks (from ${from.idTag} to ${to.idTag})")
                   case error =>
                     logger.error(
                       s"Unable to download the requested blocks range, reason: $error. " +
                         s"Range: from ${currentStable.idTag} to ${missingParent.orphanBlockHeader.idTag}"
                     )
                 }
          } yield ()
        case Nil => logger.error("No peers available to download missing blocks")
      }
    }
  }
}
