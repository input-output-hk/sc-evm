package io.iohk.scevm.consensus.pos

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusGauge
import io.iohk.scevm.metrics.instruments.Gauge

final class PrometheusPoSMetrics[F[_]] private (val stabilityParameterGauge: Gauge[F], val slotDurationGauge: Gauge[F])
    extends PoSMetrics[F]

object PrometheusPoSMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "pos"

  def apply[F[_]: Sync]: Resource[F, PrometheusPoSMetrics[F]] =
    for {
      stabilityParameter <-
        PrometheusGauge(namespace, subsystem, "stability_parameter", "Stability parameter (K) of the chain")
      slotDuration <-
        PrometheusGauge(namespace, subsystem, "slot_duration", "Slot duration (in seconds) of the chain")
    } yield new PrometheusPoSMetrics(stabilityParameter, slotDuration)
}
