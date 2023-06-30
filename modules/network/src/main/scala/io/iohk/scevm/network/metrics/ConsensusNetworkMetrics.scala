package io.iohk.scevm.network.metrics

import io.iohk.scevm.metrics.instruments.Gauge

trait ConsensusNetworkMetrics[F[_]] {
  val forkingFactorGauge: Gauge[F]
  val chainDensityGauge: Gauge[F]
}
