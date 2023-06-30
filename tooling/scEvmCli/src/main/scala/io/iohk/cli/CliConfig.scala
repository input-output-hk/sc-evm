package io.iohk.cli

import com.typesafe.config.{Config, ConfigFactory}
import sttp.model.Uri

import java.io.File
import scala.util.Try

final case class CliConfig(
    paymentSigningKeyFile: Option[String],
    runtimeConfig: CliConfig.RuntimeConfig
)

object CliConfig {

  def load(file: String): Option[CliConfig] =
    Try(ConfigFactory.parseFile(new File(file))).toOption
      .map { config =>
        CliConfig(
          Try(config.getString("paymentSigningKeyFile")).toOption,
          RuntimeConfig.fromConfig(config)
        )
      }
  final case class RuntimeConfig(
      scEvm: Option[HostConfig]
  )

  object RuntimeConfig {
    def fromConfig(config: Config): RuntimeConfig =
      RuntimeConfig(
        Try(config.getConfig("runtimeConfig").getConfig("scEvm")).toOption
          .map(HostConfig.fromConfig)
      )
  }

  val DefaultPort = 80
  final case class HostConfig(
      host: Option[String],
      port: Option[Int],
      path: Option[String],
      secure: Option[Boolean]
  ) {
    def getUri: Option[Uri] =
      for (host <- host)
        yield Uri(
          scheme = if (secure.contains(true)) "https" else "http",
          host = host,
          port = port.getOrElse(DefaultPort),
          path = path.map(_.split("/").filter(_.isEmpty).toSeq).getOrElse(Nil)
        )
  }

  object HostConfig {
    def fromConfig(config: Config): HostConfig =
      HostConfig(
        host = Try(config.getString("host")).toOption,
        port = Try(config.getInt("port")).toOption,
        path = Try(config.getString("path")).toOption,
        secure = Try(config.getBoolean("secure")).toOption
      )

  }
}
