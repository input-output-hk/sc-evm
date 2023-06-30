package io.iohk.scevm.network

import io.iohk.scevm.network.PeerConfig.ConnectionLimits

import scala.concurrent.duration.FiniteDuration

final case class PeerConfig(
    connectRetryDelay: FiniteDuration,
    connectMaxRetries: Int,
    disconnectPoisonPillTimeout: FiniteDuration,
    waitForHelloTimeout: FiniteDuration,
    waitForStatusTimeout: FiniteDuration,
    hostConfiguration: HostConfig,
    rlpxConfiguration: RLPxConfig,
    updateNodesInitialDelay: FiniteDuration,
    updateNodesInterval: FiniteDuration,
    statSlotDuration: FiniteDuration,
    statSlotCount: Int,
    connectionLimits: ConnectionLimits
)

object PeerConfig {

  final case class ConnectionLimits(
      minOutgoingPeers: Int,
      maxOutgoingPeers: Int,
      maxIncomingPeers: Int,
      maxPendingPeers: Int,
      pruneIncomingPeers: Int,
      minPruneAge: FiniteDuration
  )
}
