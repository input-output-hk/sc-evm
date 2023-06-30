package io.iohk.scevm.consensus.metrics

import io.iohk.scevm.metrics.instruments.{Counter, Gauge, Histogram}

trait BlockMetrics[F[_]] {
  val durationOfBlockCreationHistogram: Histogram[F]
  val blockCreationErrorCounter: Counter[F]
  val blockTransactionsGauge: Gauge[F]
  val gasLimitGauge: Gauge[F]
  val gasUsedGauge: Gauge[F]
  val durationOfBlockExecutionHistogram: Histogram[F]
  val blockExecutionErrorCounter: Counter[F]
  val timeToFinalizationGauge: Gauge[F]
  val executedBlocksCounter: Counter[F]
}
