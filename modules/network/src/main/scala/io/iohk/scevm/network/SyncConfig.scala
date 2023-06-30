package io.iohk.scevm.network

import cats.Show
import com.typesafe.config.Config
import io.iohk.scevm.network.SyncConfig.SyncMode

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

final case class SyncConfig(
    peerResponseTimeout: FiniteDuration,
    messageSourceBuffer: Int,
    scoringAncestorOffset: Int,
    startSync: Boolean,
    syncMode: SyncMode,
    gossipCacheFactor: Double,
    networkStableHeaderResolverRetryCount: Int,
    networkStableHeaderResolverRetryDelay: FiniteDuration,
    enableLowDensitySyncFallback: Boolean,
    networkStableHeaderResolverLowDensityThreshold: Double,
    maxHeadersInMemoryCount: Int
)

object SyncConfig {
  def fromConfig(config: Config): SyncConfig = {
    val syncConfig = config.getConfig("sync")
    SyncConfig(
      peerResponseTimeout = syncConfig.getDuration("peer-response-timeout").toScala,
      messageSourceBuffer = syncConfig.getInt("message-source-buffer"),
      scoringAncestorOffset = syncConfig.getInt("scoring-ancestor-offset"),
      startSync = syncConfig.getBoolean("start-sync"),
      syncMode = toSyncMode(syncConfig.getString("sync-mode")),
      gossipCacheFactor = syncConfig.getDouble("gossip-cache-factor"),
      networkStableHeaderResolverRetryCount = syncConfig.getInt("network-stable-header-resolver-retry-count"),
      networkStableHeaderResolverRetryDelay =
        syncConfig.getDuration("network-stable-header-resolver-retry-delay").toScala,
      enableLowDensitySyncFallback = syncConfig.getBoolean("enable-low-density-sync-fallback"),
      networkStableHeaderResolverLowDensityThreshold =
        syncConfig.getDouble("network-stable-header-resolver-low-density-threshold"),
      maxHeadersInMemoryCount = syncConfig.getInt("max-headers-in-memory-count")
    )
  }

  private def toSyncMode(mode: String): SyncMode = mode match {
    case "full" => SyncMode.Full
    case "fast" => SyncMode.Fast
    case _      => SyncMode.Full
  }

  sealed trait SyncMode
  object SyncMode {
    case object Full extends SyncMode
    case object Fast extends SyncMode

    implicit val show: Show[SyncMode] = Show.show[SyncMode] {
      case Full => "full-sync mode"
      case Fast => "fast-sync mode"
    }
  }
}
