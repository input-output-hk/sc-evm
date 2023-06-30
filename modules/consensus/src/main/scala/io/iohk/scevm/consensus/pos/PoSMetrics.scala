package io.iohk.scevm.consensus.pos

import io.iohk.scevm.metrics.instruments.Gauge

trait PoSMetrics[F[_]] {
  val stabilityParameterGauge: Gauge[F]
  val slotDurationGauge: Gauge[F]
}
