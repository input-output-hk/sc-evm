package io.iohk.scevm.consensus.pos

import cats.MonadThrow
import cats.effect.{Async, Sync}
import cats.syntax.all._
import fs2.concurrent.{Signal, SignallingRef}
import io.iohk.scevm.consensus.domain.ForwardBranchSlice
import io.iohk.scevm.consensus.metrics.{BlockMetrics, ConsensusMetrics, TimeToFinalizationTracker}
import io.iohk.scevm.consensus.pos.ConsensusService.BetterBranch
import io.iohk.scevm.db.storage.{
  BlocksReader,
  ObftAppStorage,
  StableNumberMappingStorage,
  StableTransactionMappingStorage
}
import io.iohk.scevm.domain._
import io.iohk.scevm.storage.metrics.StorageMetrics
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import CurrentBranchService.InconsistentStableNumberMappingStorage

/** Entry point for updating the current branch in memory and storage
  */
trait CurrentBranchService[F[_]] extends ConsensusService.BranchUpdateListener[F] {
  def newBranch(betterBranch: BetterBranch): F[Unit]

  /** Update current branch and storage with the new stable blocks.
    * Range.from is expected to be the previous stable header.
    * Range.to will become the new stable header.
    */
  def newStableBlocks(forwardBranchSlice: ForwardBranchSlice[F], afterStartupSync: Boolean): F[Unit]
  def signal: CurrentBranch.Signal[F]
}

class CurrentBranchServiceImpl[F[_]: Sync: LoggerFactory](
    appStorage: ObftAppStorage[F],
    blocksReader: BlocksReader[F],
    stableNumberToHashMappingStorage: StableNumberMappingStorage[F],
    transactionMappingStorage: StableTransactionMappingStorage[F],
    currentBranch: SignallingRef[F, CurrentBranch],
    blockMetrics: BlockMetrics[F],
    storageMetrics: StorageMetrics[F],
    timeToFinalizationTracker: TimeToFinalizationTracker[F],
    consensusMetrics: ConsensusMetrics[F]
) extends CurrentBranchService[F] {

  private val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def newBranch(betterBranch: BetterBranch): F[Unit] =
    for {
      _ <- betterBranch.stablePart.traverse(updateIndexes)
      _ <- betterBranch.newStable.fold(().pure)(computeMetricsForStableBlock)
      _ <- blocksReader.getBlock(betterBranch.newBest.hash).flatMap {
             case Some(newBestBlock) => computeMetricsForBestBlock(newBestBlock)
             case None =>
               MonadThrow[F].raiseError[Unit](
                 new RuntimeException(s"The new best block ${betterBranch.newBest.idTag} is absent from the storage")
               )
           }
      _ <- currentBranch.update(_.update(betterBranch))
      _ <- betterBranch.newStable.traverse_(header => appStorage.putLatestStableBlockHash(header.hash))
    } yield ()

  override def newStableBlocks(forwardBranchSlice: ForwardBranchSlice[F], afterStartupSync: Boolean): F[Unit] =
    for {
      lastBlockOpt <- forwardBranchSlice.blockStream
                        .evalTap(updateIndexes)
                        .compile
                        .last
      _ <- lastBlockOpt.traverse(block => computeMetricsForStableBlock(block.header)).whenA(!afterStartupSync)
      _ <- lastBlockOpt.traverse(computeMetricsForBestBlock)
      _ <- lastBlockOpt.traverse(block => appStorage.putLatestStableBlockHash(block.hash))
      _ <- lastBlockOpt.traverse(lastBlock => currentBranch.update(_ => CurrentBranch(lastBlock.header)))
    } yield ()

  override val signal: Signal[F, CurrentBranch] = currentBranch

  private def updateIndexes(newStableBlock: ObftBlock): F[Unit] =
    if (newStableBlock.number.value == 0) {
      // ignore genesis block
      Sync[F].unit
    } else {
      for {
        previousBlockNumber <- newStableBlock.number.previous.pure
        maybeBlockHash      <- stableNumberToHashMappingStorage.getBlockHash(previousBlockNumber)
        _ <- maybeBlockHash match {
               case None =>
                 InconsistentStableNumberMappingStorage(newStableBlock, previousBlockNumber).raiseError[F, Unit]
               case Some(_) =>
                 storageMetrics.writeTimeForStableIndexesUpdate.observe(
                   Sync[F].delay {
                     val baseDbAction = stableNumberToHashMappingStorage
                       .insertBlockHashWithoutCommit(newStableBlock.number, newStableBlock.hash)
                     newStableBlock.body.transactionList.zipWithIndex
                       .foldLeft(baseDbAction) { case (acc, (transaction, index)) =>
                         acc.and(
                           transactionMappingStorage
                             .put(transaction.hash, TransactionLocation(newStableBlock.hash, index))
                         )
                       }
                       .commit()
                   }
                 )
             }
      } yield ()
    }

  private def computeMetricsForStableBlock(electedBlock: ObftHeader): F[Unit] =
    for {
      _                       <- consensusMetrics.latestStableBlockNumberGauge.set(electedBlock.number.toLong)
      _                       <- consensusMetrics.timeBetweenStableBlocksHistogram.observe
      maybeTimeToFinalization <- timeToFinalizationTracker.evaluate(electedBlock.hash)
      _ <- maybeTimeToFinalization match {
             case Some(time) => blockMetrics.timeToFinalizationGauge.set(time.toSeconds)
             case None =>
               logger.warn(
                 s"Block ${electedBlock.hash} is missing from the header tracker map." +
                   s" The time for finalization metric cannot be computed for this block."
               )
           }
      _ <- logger.info(show"New stable $electedBlock")
    } yield ()

  private def computeMetricsForBestBlock(newBest: ObftBlock): F[Unit] =
    for {
      _ <- consensusMetrics.latestBestBlockNumberGauge.set(newBest.number.toLong)
      _ <- blockMetrics.gasLimitGauge.set(newBest.header.gasLimit.toLong)
      _ <- blockMetrics.gasUsedGauge.set(newBest.header.gasUsed.toLong)
      _ <- blockMetrics.blockTransactionsGauge.set(newBest.body.transactionList.size.toLong)
      _ <- consensusMetrics.timeBetweenBestBlocksHistogram.observe
      _ <- logger.info(show"New best alternative $newBest")
    } yield ()
}

object CurrentBranchService {
  def apply[F[_]](implicit ev: CurrentBranchService[F]): CurrentBranchService[F] = ev

  // scalastyle:off parameter.number
  def init[F[_]: Async: LoggerFactory](
      appStorage: ObftAppStorage[F],
      blocksReader: BlocksReader[F],
      stableNumberToHashMappingStorage: StableNumberMappingStorage[F],
      transactionMappingStorage: StableTransactionMappingStorage[F],
      initialHeader: ObftHeader,
      blockMetrics: BlockMetrics[F],
      storageMetrics: StorageMetrics[F],
      timeToFinalizationTracker: TimeToFinalizationTracker[F],
      consensusMetrics: ConsensusMetrics[F]
  ): F[CurrentBranchServiceImpl[F]] =
    for {
      stableHashOpt  <- appStorage.getLatestStableBlockHash
      stableBlockOpt <- stableHashOpt.flatTraverse(blocksReader.getBlock)
      stableHeader    = stableBlockOpt.fold(initialHeader)(_.header)
      _              <- Sync[F].whenA(stableHashOpt.isEmpty)(appStorage.putLatestStableBlockHash(stableHeader.hash))
      signallingRef  <- SignallingRef[F].of(CurrentBranch(stableHeader))
    } yield new CurrentBranchServiceImpl(
      appStorage,
      blocksReader,
      stableNumberToHashMappingStorage,
      transactionMappingStorage,
      signallingRef,
      blockMetrics,
      storageMetrics,
      timeToFinalizationTracker,
      consensusMetrics
    )
  // scalastyle:on parameter.number

  final case class InconsistentStableNumberMappingStorage(blockToInsert: ObftBlock, missingBlockNumber: BlockNumber)
      extends Exception(
        show"Inconsistent StableNumberMapping storage, block number $missingBlockNumber not found when trying to insert ${blockToInsert.header}"
      )
}
