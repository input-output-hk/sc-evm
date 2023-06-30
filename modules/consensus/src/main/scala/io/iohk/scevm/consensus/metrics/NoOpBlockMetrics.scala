package io.iohk.scevm.consensus.metrics

import cats.Applicative
import io.iohk.scevm.metrics.instruments.{Counter, Gauge, Histogram}

final case class NoOpBlockMetrics[F[_]: Applicative]() extends BlockMetrics[F] {
  override val blockTransactionsGauge: Gauge[F]                = Gauge.noop
  override val gasLimitGauge: Gauge[F]                         = Gauge.noop
  override val gasUsedGauge: Gauge[F]                          = Gauge.noop
  override val durationOfBlockExecutionHistogram: Histogram[F] = Histogram.noop
  override val blockExecutionErrorCounter: Counter[F]          = Counter.noop
  override val durationOfBlockCreationHistogram: Histogram[F]  = Histogram.noop
  override val blockCreationErrorCounter: Counter[F]           = Counter.noop
  override val timeToFinalizationGauge: Gauge[F]               = Gauge.noop
  override val executedBlocksCounter: Counter[F]               = Counter.noop
}
