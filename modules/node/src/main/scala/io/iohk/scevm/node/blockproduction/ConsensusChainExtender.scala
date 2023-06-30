package io.iohk.scevm.node.blockproduction

import cats.Applicative
import cats.data.{EitherT, OptionT}
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.consensus.metrics.TimeToFinalizationTracker
import io.iohk.scevm.consensus.pos.ConsensusService.ConnectedBranch
import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranch}
import io.iohk.scevm.domain.{LeaderSlotEvent, NotLeaderSlotEvent, ObftBlock, SignedTransaction, SlotEvent}
import io.iohk.scevm.ledger.BlockImportService
import io.iohk.scevm.ledger.BlockImportService.ValidatedBlock
import io.iohk.scevm.ledger.blockgeneration.BlockGenerator
import io.iohk.scevm.logging.TracedLogger
import io.iohk.scevm.network.BlockBroadcaster
import io.iohk.scevm.network.metrics.{BlockPropagationMetrics, ConsensusNetworkMetricsUpdater}
import io.iohk.scevm.sync.NextBlockTransactions
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}

import ChainExtender.ChainExtensionError.{CannotImportOwnBlock, ConsensusError, NoTransactionsError}
import ChainExtender.ChainExtensionResult.{ChainExtended, ChainNotExtended}
import ChainExtender.{ChainExtensionError, ChainExtensionResult}

class ConsensusChainExtender[F[_]: Sync: Trace: LoggerFactory](
    blockGenerator: BlockGenerator[F],
    nextBlockTransactions: NextBlockTransactions[F],
    blockImportService: BlockImportService[F],
    consensusService: ConsensusService[F],
    timeToFinalizationTracker: TimeToFinalizationTracker[F],
    blockPropagationMetrics: BlockPropagationMetrics[F],
    consensusNetworkMetricsUpdater: ConsensusNetworkMetricsUpdater[F],
    blockBroadcaster: BlockBroadcaster[F]
) extends ChainExtender[F] {

  private val log: StructuredLogger[F] = TracedLogger(LoggerFactory.getLogger[F])

  override def extendChainIfLeader(
      slotEvent: SlotEvent,
      currentBranch: CurrentBranch
  ): F[Either[ChainExtensionError, ChainExtensionResult]] =
    slotEvent match {
      case _: NotLeaderSlotEvent => Applicative[F].pure(Right(ChainNotExtended))
      case slotEvent: LeaderSlotEvent =>
        Trace[F].span("ConsensusBlockProducer::generateImportResolveAndBroadcastBlock") {
          generateImportResolveAndBroadcastBlock(slotEvent, currentBranch).value.widen
        }
    }

  private def generateImportResolveAndBroadcastBlock(
      slotEvent: LeaderSlotEvent,
      currentBranch: CurrentBranch
  ): EitherT[F, ChainExtensionError, ChainExtended.type] = for {
    timer <- EitherT.liftF(blockPropagationMetrics.blockGenerationHistogram.createTimer)
    txs <- EitherT(nextBlockTransactions.getNextTransactions(slotEvent.slot, slotEvent.keySet, currentBranch.best))
             .leftMap(NoTransactionsError(_, slotEvent.slot))
    block <- EitherT.liftF(generateBlock(slotEvent, currentBranch, txs))
    _     <- EitherT.liftF(timeToFinalizationTracker.track(block.hash))
    importedBlock <- OptionT(blockImportService.importBlock(ValidatedBlock(block)))
                       .toRight(CannotImportOwnBlock(slotEvent.slot))
    _               <- EitherT.liftF(log.trace(s"Imported block ${importedBlock.block.fullToString}"))
    consensusResult <- EitherT.liftF(consensusService.resolve(importedBlock, currentBranch))
    _               <- EitherT.liftF(timer.observe())
    _               <- EitherT.liftF(consensusNetworkMetricsUpdater.update(consensusResult))
    _ <- consensusResult match {
           case ConnectedBranch(_, Some(_)) => EitherT.liftF(Applicative[F].unit)
           case ConnectedBranch(_, None) =>
             EitherT.liftF(log.warn("newly generated block is not part of the best branch"))
           case ConsensusService.DisconnectedBranchMissingParent(_, _) =>
             EitherT.leftT(ConsensusError("[BUG] missing parent", slotEvent.slot))
           case ConsensusService.ForkedBeyondStableBranch(_) =>
             EitherT.leftT(ConsensusError("forked beyond stable branch", slotEvent.slot))
         }
    _ <-
      EitherT.liftF(
        blockPropagationMetrics.blockPublishingHistogram.observe(blockBroadcaster.broadcastBlock(consensusResult.block))
      )
  } yield ChainExtended

  private def generateBlock(
      leaderSlotEvent: LeaderSlotEvent,
      currentBranch: CurrentBranch,
      transactions: List[SignedTransaction]
  ): F[ObftBlock] =
    for {
      _ <- log.trace(show"Generating block for ${leaderSlotEvent.slot}")
      block <- blockGenerator.generateBlock(
                 currentBranch.best,
                 transactions,
                 leaderSlotEvent.slot,
                 leaderSlotEvent.timestamp,
                 leaderSlotEvent.pubKey,
                 leaderSlotEvent.prvKey
               )
      _ <- log.info(show"Generated block $block")
      _ <- log.debug(show"Above block's transactions=${block.body.transactionList.map(_.hash).toList}")
    } yield block
}
