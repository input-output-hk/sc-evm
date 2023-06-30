package io.iohk.scevm.network.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusHistogram
import io.iohk.scevm.metrics.instruments.Histogram

final class PrometheusBlockPropagationMetrics[F[_]: Sync] private (
    durationOfBlockGeneration: PrometheusHistogram[F],
    durationOfBlockPublishing: PrometheusHistogram[F],
    durationOfBlockToPeerActor: PrometheusHistogram[F],
    durationOfBlockToNetwork: PrometheusHistogram[F]
) extends BlockPropagationMetrics[F] {
  override val blockGenerationHistogram: Histogram[F] = durationOfBlockGeneration
  override val blockPublishingHistogram: Histogram[F] = durationOfBlockPublishing
  override val blockToPeerActor: Histogram[F]         = durationOfBlockToPeerActor
  override val blockToNetwork: Histogram[F]           = durationOfBlockToNetwork
}

object PrometheusBlockPropagationMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "block"

  // scalastyle:off method.length
  def apply[F[_]: Sync]: Resource[F, PrometheusBlockPropagationMetrics[F]] =
    for {
      blockGeneration <- PrometheusHistogram(
                           namespace,
                           subsystem,
                           "propagation_generation_time",
                           "Amount of time it takes to propagate a block (generation sub metric)",
                           withExemplars = false,
                           None,
                           None
                         )
      blockPublishing <- PrometheusHistogram(
                           namespace,
                           subsystem,
                           "propagation_publishing_time",
                           "Amount of time it takes to publish a block (generation sub metric)",
                           withExemplars = false,
                           None,
                           None
                         )
      blockToPeerActor <- PrometheusHistogram(
                            namespace,
                            subsystem,
                            "propagation_to_peeractor_time",
                            "Amount of time it takes to publish a block (to PeerActor sub metric)",
                            withExemplars = false,
                            None,
                            None
                          )
      blockToNetwork <- PrometheusHistogram(
                          namespace,
                          subsystem,
                          "propagation_to_network_time",
                          "Amount of time it takes to publish a block (to network sub metric)",
                          withExemplars = false,
                          None,
                          None
                        )
    } yield new PrometheusBlockPropagationMetrics(
      blockGeneration,
      blockPublishing,
      blockToPeerActor,
      blockToNetwork
    )
}
