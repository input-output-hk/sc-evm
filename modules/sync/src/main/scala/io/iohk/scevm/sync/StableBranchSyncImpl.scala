package io.iohk.scevm.sync

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Monad}
import fs2.concurrent.SignallingRef
import io.iohk.scevm.consensus.domain.ForwardBranchSlice
import io.iohk.scevm.consensus.pos.CurrentBranchService
import io.iohk.scevm.domain.{BlockNumber, ObftHeader}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.logging.TracedLogger
import io.iohk.scevm.network.BranchFetcher.BranchFetcherResult
import io.iohk.scevm.network.BranchFetcher.BranchFetcherResult._
import io.iohk.scevm.network.{PeerId, PeerWithInfo, SyncConfig}
import io.iohk.scevm.sync.StableBranchSync.StableBranchSyncResult._
import io.iohk.scevm.sync.StableBranchSync.{StableBranchSyncError, StableBranchSyncResult}
import io.iohk.scevm.sync.networkstable.NetworkStableHeaderResolver
import io.iohk.scevm.sync.networkstable.NetworkStableHeaderResult.{
  NetworkChainTooShort,
  NoAvailablePeer,
  StableHeaderFound,
  StableHeaderNotFound
}
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.LoggerFactory

class StableBranchSyncImpl[F[_]: Async: LoggerFactory: Trace](
    syncConfig: SyncConfig,
    syncMode: SyncMode[F],
    blockProvider: BlockProvider[F],
    currentBranchService: CurrentBranchService[F],
    peersWithInfo: SignallingRef[F, Map[PeerId, PeerWithInfo]]
) extends StableBranchSync[F] {

  private val logger = TracedLogger(LoggerFactory[F].getLoggerFromClass(this.getClass))

  private val GENESIS_BLOCK_NUMBER = BlockNumber(0)

  private val highDensityResolver = NetworkStableHeaderResolver.highDensity[F]
  private val lowDensityResolver =
    NetworkStableHeaderResolver.lowDensity[F](syncConfig.networkStableHeaderResolverLowDensityThreshold)
  private val initialResolver = if (syncConfig.enableLowDensitySyncFallback) {
    NetworkStableHeaderResolver.withFallback(
      NetworkStableHeaderResolver.withRetry(
        syncConfig.networkStableHeaderResolverRetryCount,
        syncConfig.networkStableHeaderResolverRetryDelay,
        highDensityResolver
      ),
      lowDensityResolver
    )
  } else {
    NetworkStableHeaderResolver.withRetry(
      syncConfig.networkStableHeaderResolverRetryCount,
      syncConfig.networkStableHeaderResolverRetryDelay,
      highDensityResolver
    )
  }
  private val followUpResolver = if (syncConfig.enableLowDensitySyncFallback) {
    NetworkStableHeaderResolver.withRetry(
      syncConfig.networkStableHeaderResolverRetryCount,
      syncConfig.networkStableHeaderResolverRetryDelay,
      NetworkStableHeaderResolver.withFallback(highDensityResolver, lowDensityResolver)
    )
  } else initialResolver

  /** Catch up to the network stable block.
    * Identify the network stable block,
    * download all the intermediate blocks (header + body) between
    * the current node's stable block and the network stable block,
    * and execute them.
    * As this operation can take quite some time, loop until the
    * local node and the network stable blocks are the same.
    * This will always respect the initial stable block invariant.
    * If the network stable block is based on a branch forking before
    * the local node stable block, the method will return with an error.
    *
    * @return a status
    */
  override def catchup(): F[StableBranchSync.StableBranchSyncResult] = {
    val ret = (for {
      initialLocalStable <- EitherT.liftF(currentBranchService.signal.get.map(_.stable))
      _                  <- EitherT.liftF(logger.info(show"Starting sync from $initialLocalStable"))
      (from, to)         <- EitherT(catchup(initialResolver, initialLocalStable, initialLocalStable))
      _                  <- EitherT.liftF(logger.info(show"Download and execution from $from to $to successful"))
      _                  <- EitherT(updateStableBranch(from, to))
      _                  <- EitherT.liftF[F, StableBranchSyncError, Unit](logger.info(show"New local stable block: $to"))
    } yield Success(from, to)).value
    ret.map(
      _.merge
    )
  }

  /** Look for the network stable block, download the branch between the checkpoint and
    * the network stable, and execute it.
    * Loop until the local and network stable blocks are the same.
    * If the checkpoint block is not on the same branch as the network stable block,
    * restart the process with the initial local stable block.
    * This allows to respect the fact that only the initial stable block is invariant,
    * while preventing the systematic traversal and execution of all the already processed
    * blocks when the network stable is moving further on the same branch.
    * @param initialStable The current stored stable. The process won't switch to a chain not containing that block.
    * @param checkpoint the already reached checkpoint block. The method can overwrite it when the network stable branch starts
    *                      before it.
    * @return a status
    */
  private def catchup(
      resolver: NetworkStableHeaderResolver[F],
      initialStable: ObftHeader,
      checkpoint: ObftHeader
  ): F[Either[StableBranchSyncError, (ObftHeader, ObftHeader)]] =
    for {
      networkStableResultEither <- resolver.resolve(peersWithInfo)
      result <- networkStableResultEither match {
                  // initial network start
                  case NetworkChainTooShort if initialStable.number == GENESIS_BLOCK_NUMBER =>
                    logger.info(
                      "All connected peers present a chain that is too short for network stable header resolution, " +
                        "local node is at genesis: new network start detected"
                    ) >>
                      Monad[F].pure(Left(NetworkInitialization))
                  case NetworkChainTooShort =>
                    logger.warn(
                      "All connected peers present a chain that is too short for network stable header resolution: unable to find a network stable header"
                    ) >>
                      Monad[F].pure(Left(NetworkInitialization))
                  // unable to compute a proper network stable block
                  case StableHeaderNotFound(minimumScore) =>
                    logger.warn(s"Unable to find a network stable header with a score of at least $minimumScore") >>
                      Monad[F].pure(Left(NetworkStableNotFound))
                  case NoAvailablePeer =>
                    logger.warn("No peer available to sync to") >>
                      Monad[F].pure(Left(StableBranchSyncResult.NoAvailablePeer))
                  // the network stable is the same as the node stable, sync is finished
                  case StableHeaderFound(networkStable, _, _) if checkpoint == networkStable =>
                    logger.info(show"New sync checkpoint reached: $checkpoint") >>
                      Monad[F].pure(Right((initialStable, checkpoint)))
                  // the network stable is different than the current node one, download and execute missing blocks
                  case StableHeaderFound(networkStable, score, peerIdCandidates) =>
                    logger.info(
                      show"Discovered new network stable header $networkStable with score=$score, syncing from $checkpoint with peers ${peerIdCandidates.toList
                        .mkString(", ")}"
                    ) >>
                      retrieveNetworkStableAndLoop(initialStable, checkpoint, networkStable, peerIdCandidates)
                }
    } yield result

  /** Fetch the branch from the checkpoint and the network stable.
    * If connected, execute it and loop back to the catchup process.
    * If disconnected, use the initial local stable as a new checkpoint and restart.
    * If disconnected and already using the initial local stable as a checkpoint, raise an issue about the fork.
    * @param initialStable the local stable header at the beginning of the sync process
    * @param checkpoint the current branch tip downloaded and executed by the sync process
    * @param networkStable the target network stable
    * @return either an error, or the tuple (from, to) of the downloaded and executed branch.
    */
  // scalastyle:off method.length
  private def retrieveNetworkStableAndLoop(
      initialStable: ObftHeader,
      checkpoint: ObftHeader,
      networkStable: ObftHeader,
      peerIdCandidates: NonEmptyList[PeerId]
  ): F[Either[StableBranchSyncError, (ObftHeader, ObftHeader)]] =
    for {
      fetchResult: BranchFetcherResult <- Trace[F].span("StableBranchSync::downloadBlocks") {
                                            syncMode.fetchBranch(checkpoint, networkStable, peerIdCandidates)
                                          }
      result <- fetchResult match {
                  // network stable extends the branch of the local checkpoint
                  case Connected(from, to) =>
                    val numBlocksDownloaded = to.number.distance(from.number).value
                    logger.debug(
                      show"$numBlocksDownloaded blocks downloaded from $from to $to, continuing with validation"
                    ) >>
                      Trace[F].span("StableBranchSync::validation") {
                        syncMode
                          .validateBranch(from, to, peerIdCandidates)
                          .flatMap[Either[StableBranchSyncError, (ObftHeader, ObftHeader)]] {
                            case Left(error) => Monad[F].pure(Left(ValidationError(error.toString)))
                            case Right(_)    => catchup(followUpResolver, initialStable, to)
                          }
                      }

                  // the local checkpoint doesn't belong to the network stable branch, reset to node initial stable or fail
                  case Disconnected(checkpoint, actualFrom, to) =>
                    if (initialStable == checkpoint) {
                      logger.error(
                        show"Unrecoverable error: Local branch does not belong to network stable branch, " +
                          show"checkpoint=$checkpoint is initial local stable, target=$to, diverging header=$actualFrom"
                      ) >>
                        Monad[F].pure(Left(CantConnectToNodeStable(initialStable, networkStable)))
                    } else {
                      logger.info(
                        show"Target branch tip $to is not an extension from current sync checkpoint " +
                          show"$checkpoint, retrying from node stable $initialStable"
                      ) >>
                        catchup(initialResolver, initialStable, initialStable) // reset branch resolution
                    }
                  case RecoverableError(details) =>
                    logger.warn(details) >>
                      catchup(initialResolver, initialStable, initialStable) // reset branch resolution
                  case FatalError(message) =>
                    logger.error(s"Fatal error while fetching a branch: $message") >>
                      Monad[F].pure(Left(BranchFetcherError(message)))
                  case InvalidRange =>
                    val msg = show"Invalid request range from=$checkpoint to=$networkStable"
                    logger.error(msg) >>
                      Monad[F].pure(Left(BranchFetcherError(msg)))
                }
    } yield result

  private def updateStableBranch(from: ObftHeader, to: ObftHeader): F[Either[StableBranchSyncError, Unit]] =
    ForwardBranchSlice.build(syncConfig.maxHeadersInMemoryCount, blockProvider, from.hash, to.hash).flatMap {
      case Left(error) => Applicative[F].pure(Left(StorageError(show"$error")))
      case Right(forwardBranchSlice) =>
        currentBranchService.newStableBlocks(forwardBranchSlice, afterStartupSync = true).as(Right(()))
    }
}
