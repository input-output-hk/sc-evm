package io.iohk.scevm.network.metrics

import cats.effect.Sync
import io.iohk.scevm.metrics.instruments.Histogram

final case class NoOpBlockPropagationMetrics[F[_]: Sync]() extends BlockPropagationMetrics[F] {
  override val blockGenerationHistogram: Histogram[F] = Histogram.noop
  override val blockPublishingHistogram: Histogram[F] = Histogram.noop
  override val blockToPeerActor: Histogram[F]         = Histogram.noop
  override val blockToNetwork: Histogram[F]           = Histogram.noop
}
