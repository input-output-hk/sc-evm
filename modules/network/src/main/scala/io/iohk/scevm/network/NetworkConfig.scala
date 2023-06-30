package io.iohk.scevm.network

import com.typesafe.config.Config
import io.iohk.scevm.network.NetworkConfig.ServerConfig
import io.iohk.scevm.network.PeerConfig.ConnectionLimits

import java.net.{InetSocketAddress, URI}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters.JavaDurationOps

final case class NetworkConfig(
    bootstrapNodes: Set[URI],
    peer: PeerConfig,
    server: ServerConfig
)

object NetworkConfig {
  def fromConfig(config: Config): NetworkConfig = {
    val networkConfig = config.getConfig("network")
    val bootstrapNodes: Set[URI] =
      networkConfig.getStringList("bootstrap-nodes").asScala.toSet.map((uri: String) => new URI(uri))
    new NetworkConfig(
      bootstrapNodes = bootstrapNodes,
      peer = peerConfiguration(networkConfig),
      server = serverConfig(networkConfig)
    )
  }

  private def peerConfiguration(networkConfig: Config): PeerConfig = {
    val peerConfig = networkConfig.getConfig("peer")

    PeerConfig(
      connectRetryDelay = peerConfig.getDuration("connect-retry-delay").toScala,
      connectMaxRetries = peerConfig.getInt("connect-max-retries"),
      disconnectPoisonPillTimeout = peerConfig.getDuration("disconnect-poison-pill-timeout").toScala,
      waitForHelloTimeout = peerConfig.getDuration("wait-for-hello-timeout").toScala,
      waitForStatusTimeout = peerConfig.getDuration("wait-for-status-timeout").toScala,
      updateNodesInitialDelay = peerConfig.getDuration("update-nodes-initial-delay").toScala,
      updateNodesInterval = peerConfig.getDuration("update-nodes-interval").toScala,
      statSlotDuration = peerConfig.getDuration("stat-slot-duration").toScala,
      statSlotCount = peerConfig.getInt("stat-slot-count"),
      hostConfiguration = hostConfiguration(peerConfig),
      rlpxConfiguration = rlpxConfiguration(peerConfig),
      connectionLimits = connectionLimits(peerConfig)
    )
  }

  private def rlpxConfiguration(peerConfig: Config): RLPxConfig =
    RLPxConfig(
      waitForHandshakeTimeout = peerConfig.getDuration("wait-for-handshake-timeout").toScala,
      waitForTcpAckTimeout = peerConfig.getDuration("wait-for-tcp-ack-timeout").toScala
    )

  private def hostConfiguration(peerConfig: Config): HostConfig =
    HostConfig(
      maxBlocksHeadersPerMessage = peerConfig.getInt("max-blocks-headers-per-message"),
      maxBlocksBodiesPerMessage = peerConfig.getInt("max-blocks-bodies-per-message"),
      maxBlocksPerReceiptsMessage = peerConfig.getInt("max-blocks-per-receipts-message"),
      maxBlocksPerFullBlocksMessage = peerConfig.getInt("max-blocks-per-full-blocks-message"),
      maxStableHeadersPerMessage = peerConfig.getInt("max-stable-headers-per-message"),
      maxMptComponentsPerMessage = peerConfig.getInt("max-mpt-components-per-message"),
      parallelism = peerConfig.getInt("blockchain-host-parallelism")
    )

  private def connectionLimits(peerConfig: Config): ConnectionLimits = {
    val minOutgoingPeers: Int       = peerConfig.getInt("min-outgoing-peers")
    val maxOutgoingPeers: Int       = peerConfig.getInt("max-outgoing-peers")
    val maxIncomingPeers: Int       = peerConfig.getInt("max-incoming-peers")
    val maxPendingPeers: Int        = peerConfig.getInt("max-pending-peers")
    val pruneIncomingPeers: Int     = peerConfig.getInt("prune-incoming-peers")
    val minPruneAge: FiniteDuration = peerConfig.getDuration("min-prune-age").toScala
    ConnectionLimits(
      minOutgoingPeers = minOutgoingPeers,
      maxOutgoingPeers = maxOutgoingPeers,
      maxIncomingPeers = maxIncomingPeers,
      maxPendingPeers = maxPendingPeers,
      pruneIncomingPeers = pruneIncomingPeers,
      minPruneAge = minPruneAge
    )
  }

  private def serverConfig(networkConfig: Config): ServerConfig = {
    val serverConfig = networkConfig.getConfig("server-address")
    ServerConfig(interface = serverConfig.getString("interface"), port = serverConfig.getInt("port"))
  }

  final case class ServerConfig(interface: String, port: Int) {
    def listenAddress: InetSocketAddress = new InetSocketAddress(interface, port)
  }
}
