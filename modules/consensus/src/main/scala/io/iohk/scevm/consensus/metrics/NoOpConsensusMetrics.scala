package io.iohk.scevm.consensus.metrics

import cats.Applicative
import io.iohk.scevm.metrics.instruments.{Gauge, IntervalHistogram}

final case class NoOpConsensusMetrics[F[_]: Applicative]() extends ConsensusMetrics[F] {
  override val slotGauge: Gauge[F]                                    = Gauge.noop
  override val latestBestBlockNumberGauge: Gauge[F]                   = Gauge.noop
  override val timeBetweenBestBlocksHistogram: IntervalHistogram[F]   = IntervalHistogram.noop
  override val latestStableBlockNumberGauge: Gauge[F]                 = Gauge.noop
  override val timeBetweenStableBlocksHistogram: IntervalHistogram[F] = IntervalHistogram.noop
  override val branchDiscardedRuntimeError: Gauge[F]                  = Gauge.noop
}
