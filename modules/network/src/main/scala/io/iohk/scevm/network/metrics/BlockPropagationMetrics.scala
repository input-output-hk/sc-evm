package io.iohk.scevm.network.metrics

import io.iohk.scevm.metrics.instruments.Histogram

trait BlockPropagationMetrics[F[_]] {
  val blockGenerationHistogram: Histogram[F]
  val blockPublishingHistogram: Histogram[F]
  val blockToPeerActor: Histogram[F]
  val blockToNetwork: Histogram[F]
}
