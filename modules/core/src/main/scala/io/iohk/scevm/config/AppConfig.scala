package io.iohk.scevm.config

import com.typesafe.config.{Config => TypesafeConfig}
import io.iohk.scevm.config.ConfigOps.ConfigurationExtensions
import io.iohk.scevm.utils.VersionInfo

final case class AppConfig(
    clientId: String,
    clientVersion: String,
    nodeKeyFile: String
)

object AppConfig {
  def fromConfig(config: TypesafeConfig): AppConfig =
    AppConfig(
      clientId = VersionInfo.nodeName(config.getStringOpt("client-identity")),
      clientVersion = VersionInfo.nodeName(),
      nodeKeyFile = config.getString("node-key-file")
    )
}
