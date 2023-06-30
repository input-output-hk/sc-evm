package io.iohk.scevm.metrics

import com.typesafe.config.Config

final case class InstrumentationConfig(metricsConfig: MetricsConfig, tracingConfig: TracingConfig)

object InstrumentationConfig {
  def fromConfig(config: Config): InstrumentationConfig =
    InstrumentationConfig(metricsConfig = MetricsConfig(config), tracingConfig = TracingConfig.fromConfig(config))
}
