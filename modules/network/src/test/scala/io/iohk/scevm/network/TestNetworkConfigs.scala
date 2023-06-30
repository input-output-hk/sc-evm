package io.iohk.scevm.network
import io.iohk.scevm.network.NetworkConfig.ServerConfig
import io.iohk.scevm.network.PeerConfig.ConnectionLimits

import scala.concurrent.duration._

object TestNetworkConfigs {
  val networkConfig: NetworkConfig = NetworkConfig(
    Set.empty,
    PeerConfig(
      connectRetryDelay = 1.second,
      connectMaxRetries = 1,
      disconnectPoisonPillTimeout = 1.second,
      waitForHelloTimeout = 3.seconds,
      waitForStatusTimeout = 30.seconds,
      hostConfiguration = HostConfig(100, 100, 100, 1000, 100, 200, 10),
      rlpxConfiguration = RLPxConfig(3.seconds, 5.seconds),
      updateNodesInitialDelay = 5.seconds,
      updateNodesInterval = 10.seconds,
      statSlotDuration = 600.seconds,
      statSlotCount = 72,
      connectionLimits = ConnectionLimits(3, 3, 1, 1, 1, 0.days)
    ),
    ServerConfig(interface = "0.0.0.0", port = 9076)
  )

}
