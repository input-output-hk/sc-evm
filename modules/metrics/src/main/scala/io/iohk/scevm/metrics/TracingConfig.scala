package io.iohk.scevm.metrics

import com.typesafe.config.Config

final case class TracingConfig(
    enabled: Boolean,
    host: String,
    port: Int
)

object TracingConfig {
  object Keys {
    final val Tracing = "instrumentation.tracing"
    final val Enabled = "enabled"
    final val Host    = "export-host"
    final val Port    = "export-port"
  }

  def fromConfig(config: Config): TracingConfig = {
    val tracingConfig = config.getConfig(Keys.Tracing)

    TracingConfig(
      enabled = tracingConfig.getBoolean(Keys.Enabled),
      host = tracingConfig.getString(Keys.Host),
      port = tracingConfig.getInt(Keys.Port)
    )
  }
}
