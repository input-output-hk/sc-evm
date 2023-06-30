package io.iohk.scevm.network.metrics

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.consensus.pos.ConsensusService
import io.iohk.scevm.consensus.pos.ConsensusService.ConsensusResult
import io.iohk.scevm.network.domain.ChainDensity

trait ConsensusNetworkMetricsUpdater[F[_]] {
  def update(consensusResult: ConsensusResult): F[Unit]
}

object ConsensusNetworkMetricsUpdater {
  def apply[F[_]: Sync](
      metrics: ConsensusNetworkMetrics[F],
      forkingFactor: ForkingFactor[F]
  ): ConsensusNetworkMetricsUpdater[F] =
    new ConsensusNetworkMetricsUpdater[F] {
      override def update(consensusResult: ConsensusResult): F[Unit] =
        (for {
          ffMetric <- forkingFactor.evaluate(consensusResult.block.header)
          _        <- metrics.forkingFactorGauge.set(ffMetric)
        } yield ()) >> {
          consensusResult match {
            case ConsensusService.ConnectedBranch(_, Some(betterBranch)) =>
              betterBranch.newStable match {
                case Some(stableHeader) =>
                  val density = ChainDensity.density(betterBranch.newBest, stableHeader)
                  metrics.chainDensityGauge.set(density)
                case None => Sync[F].unit
              }
            case _ => Sync[F].unit
          }
        }
    }
}
