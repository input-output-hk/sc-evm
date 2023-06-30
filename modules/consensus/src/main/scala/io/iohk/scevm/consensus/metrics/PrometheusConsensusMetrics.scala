package io.iohk.scevm.consensus.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.{PrometheusGauge, PrometheusIntervalHistogram}
import io.iohk.scevm.metrics.instruments.{Gauge, IntervalHistogram}

final class PrometheusConsensusMetrics[F[_]] private (
    override val slotGauge: Gauge[F],
    override val latestBestBlockNumberGauge: Gauge[F],
    override val latestStableBlockNumberGauge: Gauge[F],
    override val timeBetweenBestBlocksHistogram: IntervalHistogram[F],
    override val timeBetweenStableBlocksHistogram: IntervalHistogram[F],
    override val branchDiscardedRuntimeError: Gauge[F]
) extends ConsensusMetrics[F]

object PrometheusConsensusMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "consensus"

  // scalastyle:off method.length
  def apply[F[_]: Sync]: Resource[F, PrometheusConsensusMetrics[F]] =
    for {
      slotGauge <- PrometheusGauge(
                     namespace,
                     subsystem,
                     "slot",
                     "Current sidechain slot number"
                   )
      latestBestBlockNumber <-
        PrometheusGauge(namespace, subsystem, "latest_best_block_number", "Latest best block number")
      timeToNextBestBlock <- PrometheusIntervalHistogram(
                               namespace,
                               subsystem,
                               "time_between_best_blocks",
                               "Time between best blocks",
                               withExemplars = true,
                               None,
                               None
                             )
      latestStableBlockNumber <-
        PrometheusGauge(namespace, subsystem, "latest_stable_block_number", "Latest stable block number")
      timeToNextStableBlock <- PrometheusIntervalHistogram(
                                 namespace,
                                 subsystem,
                                 "time_between_stable_blocks",
                                 "Time between stable blocks",
                                 withExemplars = true,
                                 None,
                                 None
                               )
      branchDiscardedRuntimeError <- PrometheusGauge(
                                       namespace,
                                       subsystem,
                                       "branch_discarded_runtime_error",
                                       "How many times a branch was discarded because of a technical/runtime error"
                                     )
    } yield new PrometheusConsensusMetrics(
      slotGauge,
      latestBestBlockNumber,
      latestStableBlockNumber,
      timeToNextBestBlock,
      timeToNextStableBlock,
      branchDiscardedRuntimeError
    )
  // scalastyle:on method.length
}
