package io.iohk.scevm.network

import io.iohk.scevm.network.SyncConfig.SyncMode

import scala.concurrent.duration._

trait TestSyncConfig {
  def defaultSyncConfig: SyncConfig = SyncConfig(
    peerResponseTimeout = 30.seconds,
    messageSourceBuffer = 10,
    scoringAncestorOffset = 2,
    startSync = false,
    syncMode = SyncMode.Full,
    gossipCacheFactor = 1.0,
    networkStableHeaderResolverRetryCount = 10,
    networkStableHeaderResolverRetryDelay = 100.milliseconds,
    enableLowDensitySyncFallback = true,
    networkStableHeaderResolverLowDensityThreshold = 0.1,
    maxHeadersInMemoryCount = 10
  )

  lazy val syncConfig: SyncConfig = defaultSyncConfig
}
