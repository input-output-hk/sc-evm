package io.iohk.scevm.node

import cats.effect.implicits.genSpawnOps
import cats.effect.{Async, Deferred, Resource, Sync}
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import io.iohk.scevm.ScEvmConfig
import io.iohk.scevm.consensus.metrics.{ConsensusMetrics, TimeToFinalizationTracker}
import io.iohk.scevm.consensus.pos.ConsensusService.ConsensusResult
import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranch}
import io.iohk.scevm.domain.{ObftBlock, Slot}
import io.iohk.scevm.exec.mempool.SignedTransactionMempool
import io.iohk.scevm.ledger.BlockImportService.UnvalidatedBlock
import io.iohk.scevm.ledger._
import io.iohk.scevm.metrics.Fs2Monitoring
import io.iohk.scevm.network.NetworkModule
import io.iohk.scevm.network.metrics.ConsensusNetworkMetricsUpdater
import io.iohk.scevm.node.blockproduction.ChainExtender
import io.iohk.scevm.sidechain.ChainModule
import io.iohk.scevm.sync.{ConsensusSync, ConsensusSyncImpl}
import io.iohk.scevm.utils.Log4sOps.EitherLog4sOps
import io.iohk.scevm.utils.{NodeState, NodeStateUpdater}
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import ChainExtender.ChainExtensionError.errorShow

object ConsensusPhase {
  // scalastyle:off parameter.number
  // scalastyle:off method.length
  def run[F[_]: Async: Trace: LoggerFactory](
      nodeConfig: ScEvmConfig,
      killSignal: Deferred[F, Either[Throwable, Unit]],
      currentSlotRef: SignallingRef[F, Slot],
      networkModule: NetworkModule[F],
      chainModule: ChainModule[F],
      chainExtender: ChainExtender[F],
      obftBlockImportService: BlockImportService[F],
      consensusService: ConsensusService[F],
      mempool: SignedTransactionMempool[F],
      timeToFinalizationTracker: TimeToFinalizationTracker[F],
      nodeStateUpdater: NodeStateUpdater[F],
      consensusMetrics: ConsensusMetrics[F],
      consensusNetworkMetricsUpdater: ConsensusNetworkMetricsUpdater[F]
  )(implicit currentBranch: CurrentBranch.Signal[F]): Resource[F, Unit] =
    for {
      _ <- Resource.eval(nodeStateUpdater.setNodeState(NodeState.Running))

      consensusSync: ConsensusSync[F] <-
        ConsensusSyncImpl(
          networkModule.branchFetcher,
          networkModule.peers.map(_.keys.toList)
        )
      _ <- processBlocks(
             networkModule.blocksFromNetwork,
             obftBlockImportService,
             consensusService,
             consensusSync,
             consensusNetworkMetricsUpdater,
             timeToFinalizationTracker
           ).compile.drain
             .onError(e => killSignal.complete(Left(e)).void)
             .background
      ticker                               = chainModule.ticker
      logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLoggerFromClass(this.getClass)
      _ <-
        ticker
          .evalTap { tick =>
            for {
              _ <- currentSlotRef.set(tick.slot)
              _ <- consensusMetrics.slotGauge.set(tick.slot.number.toDouble)
            } yield ()
          }
          .evalTap(slotEvent => mempool.evict(slotEvent.slot))
          .zip(currentBranch.continuous)
          .evalMap { case (tick, currentBranch) =>
            val leaderElection: LeaderElection[F] = chainModule.getSlotLeader(_)
            SlotEventDerivation
              .tickToSlotEvent(leaderElection)(nodeConfig.posConfig.validatorPrivateKeys)(tick)
              .map(slotEvent => (slotEvent, currentBranch))
          }
          .evalMap((chainExtender.extendChainIfLeader _).tupled)
          .evalTap(logger.logLeftAsError(_))
          .compile
          .drain
          .onError(e => killSignal.complete(Left(e)).void)
          .background
    } yield ()
  // scalastyle:on parameter.number
  // scalastyle:on method.length

  private def processBlocks[F[_]: Async](
      blocksTopic: fs2.Stream[F, ObftBlock],
      blockImportService: BlockImportService[F],
      consensusService: ConsensusService[F],
      consensusSync: ConsensusSync[F],
      consensusNetworkMetricsUpdater: ConsensusNetworkMetricsUpdater[F],
      timeToFinalizationTracker: TimeToFinalizationTracker[F]
  )(implicit currentBranch: CurrentBranch.Signal[F]): fs2.Stream[F, ConsensusResult] =
    blocksTopic
      .evalTap(block => timeToFinalizationTracker.track(block.hash))
      .through(Fs2Monitoring.probe("consensus"))
      .zip(currentBranch.continuous)
      .evalMap { case (block, branch) =>
        blockImportService.importAndValidateBlock(UnvalidatedBlock(block)).map((_, branch))
      }
      .collect { case (Some(importedBlock), branch) => (importedBlock, branch) }
      .evalMap { case (importedBlock, branch) => consensusService.resolve(importedBlock, branch).map((_, branch)) }
      .evalTap { case (consensusResult, _) => consensusNetworkMetricsUpdater.update(consensusResult) }
      .evalMap {
        case (consensusResult @ ConsensusService.DisconnectedBranchMissingParent(_, missingParent), branch) =>
          consensusSync.onMissingParent(branch.stable, missingParent).as(consensusResult: ConsensusResult)
        case (consensusResult, _) => Sync[F].pure(consensusResult)
      }
  // scalastyle:off parameter.number
}
