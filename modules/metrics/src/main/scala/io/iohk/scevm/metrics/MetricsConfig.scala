package io.iohk.scevm.metrics

import com.typesafe.config.Config

final case class MetricsConfig(
    enabled: Boolean,
    metricsPort: Int
)

object MetricsConfig {

  object Keys {
    final val Metrics = "instrumentation.metrics"
    final val Enabled = "enabled"
    final val Port    = "port"
  }

  def apply(config: Config): MetricsConfig = {
    val metricsConfig = config.getConfig(Keys.Metrics)

    val enabled     = metricsConfig.getBoolean(Keys.Enabled)
    val metricsPort = metricsConfig.getInt(Keys.Port)
    MetricsConfig(
      enabled,
      metricsPort
    )
  }
}
