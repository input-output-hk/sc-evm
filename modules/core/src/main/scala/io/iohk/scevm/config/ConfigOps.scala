package io.iohk.scevm.config

import com.typesafe.config.{Config, ConfigException}

import scala.util.Try

object ConfigOps {
  implicit class ConfigurationExtensions(val config: Config) extends AnyVal {

    def optional[T](getFromConfig: Config => T): Option[T] =
      Try(getFromConfig(config))
        .map(Option(_))
        .recover { case _: ConfigException.Missing =>
          None
        }
        .get

    def getStringOpt(path: String): Option[String] = optional(_.getString(path))

    def getConfigOpt(path: String): Option[Config] = optional(_.getConfig(path))
  }
}
