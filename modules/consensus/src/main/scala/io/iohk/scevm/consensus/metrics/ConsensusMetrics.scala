package io.iohk.scevm.consensus.metrics

import io.iohk.scevm.metrics.instruments.{Gauge, IntervalHistogram}

trait ConsensusMetrics[F[_]] {
  val slotGauge: Gauge[F]
  val latestBestBlockNumberGauge: Gauge[F]
  val latestStableBlockNumberGauge: Gauge[F]
  val timeBetweenBestBlocksHistogram: IntervalHistogram[F]
  val timeBetweenStableBlocksHistogram: IntervalHistogram[F]
  val branchDiscardedRuntimeError: Gauge[F]
}
