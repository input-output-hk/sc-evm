package io.iohk.scevm.observer

import com.typesafe.config.Config
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Address
import io.iohk.scevm.metrics.MetricsConfig
import sttp.model.Uri

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

final case class ObserverConfig(
    scEvmUrl: Uri,
    interval: FiniteDuration,
    dbSync: DatasourceConfig.DbSync,
    metrics: MetricsConfig,
    syncMainchainFrom: Long,
    fuel: Asset,
    step: Int,
    bridgeContract: Address
)

object ObserverConfig {

  def parse(rawConfig: Config): ObserverConfig = {
    val metricsConfig = MetricsConfig(rawConfig)
    val database = {
      val dbSyncConfig = rawConfig.getConfig("db-sync")
      DatasourceConfig.DbSync(
        username = dbSyncConfig.getString("username"),
        password = Sensitive(dbSyncConfig.getString("password")),
        url = url(dbSyncConfig),
        driver = dbSyncConfig.getString("driver"),
        connectThreadPoolSize = dbSyncConfig.getInt("connect-thread-pool-size")
      )
    }
    val scEvmUrl = Uri.unsafeParse(rawConfig.getString("sc-evm-url"))
    val interval = rawConfig.getDuration("interval")
    val asset = Asset(
      Hex.decodeUnsafe(rawConfig.getString("fuel.policyId")),
      Hex.decodeUnsafe(rawConfig.getString("fuel.assetName"))
    )
    val syncMainchainFrom = rawConfig.getLong("sync-mainchain-from")
    val step              = rawConfig.getInt("step-in-blocks")
    val bridgeContract    = Address(rawConfig.getString("bridge-contract"))
    ObserverConfig(scEvmUrl, interval.toScala, database, metricsConfig, syncMainchainFrom, asset, step, bridgeContract)
  }

  private def url(dbSyncConfig: Config) = {
    val host   = dbSyncConfig.getString("host")
    val port   = dbSyncConfig.getInt("port")
    val dbName = dbSyncConfig.getString("name")
    s"jdbc:postgresql://$host:$port/$dbName"
  }
}
