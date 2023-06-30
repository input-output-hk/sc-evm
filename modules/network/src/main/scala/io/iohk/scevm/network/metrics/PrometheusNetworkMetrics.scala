package io.iohk.scevm.network.metrics
import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.{PrometheusCounter, PrometheusGauge, PrometheusHistogram}
import io.iohk.scevm.metrics.instruments.{Counter, Gauge, Histogram}

final case class PrometheusNetworkMetrics[F[_]] private (
    override val processingMessageDurationHistogram: Histogram[F],
    override val handshakedIncomingPeersGauge: Gauge[F],
    override val handshakedOutgoingPeersGauge: Gauge[F],
    override val sentMessagesCounter: Counter[F],
    override val discoveredPeersSize: Gauge[F],
    override val pendingPeersSize: Gauge[F],
    override val connectionAttempts: Counter[F],
    override val forkingFactorGauge: Gauge[F],
    override val chainDensityGauge: Gauge[F],
    override val syncDownloadedBlocksCounter: Counter[F]
) extends NetworkMetrics[F]

object PrometheusNetworkMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "network"

  // scalastyle:off method.length
  def apply[F[_]: Sync]: Resource[F, PrometheusNetworkMetrics[F]] =
    for {
      processingMessageDurationHistogram <- PrometheusHistogram(
                                              namespace,
                                              subsystem,
                                              "peers_message_processing_duration",
                                              "Amount of time it takes to process a message",
                                              withExemplars = true,
                                              None,
                                              None
                                            )
      handshakedIncomingPeersGauge <-
        PrometheusGauge(namespace, subsystem, "peers_incoming_handshaked_gauge", "Total incoming requests")
      handshakedOutgoingPeersGauge <-
        PrometheusGauge(namespace, subsystem, "peers_outgoing_handshaked_gauge", "Total outgoing requests")
      receivedMessagesCounter <-
        PrometheusCounter(
          namespace,
          subsystem,
          "messages_received_total",
          "Total messages received",
          withExemplars = false,
          None
        )
      sentMessagesCounter <- PrometheusCounter(
                               namespace,
                               subsystem,
                               "messages_sent_total",
                               "Total messages sent",
                               withExemplars = false,
                               None
                             )
      discoveredPeersSize <-
        PrometheusGauge(namespace, subsystem, "discovery_foundPeers_gauge", "Total discovered peers")
      pendingPeersSize <-
        PrometheusGauge(namespace, subsystem, "peers_pending_gauge", "Total discovered peers pending connection")
      connectionAttempts <-
        PrometheusCounter(
          namespace,
          subsystem,
          "connection_attempts",
          "Total connection attempts",
          withExemplars = false,
          None
        )
      forkingFactorGauge <-
        PrometheusGauge(namespace, subsystem, "forking_factor", "Current calculation of the forking factor")
      chainDensityGauge <-
        PrometheusGauge(namespace, subsystem, "chain_density", "Current calculation of the chain density")
      downloadedBlocksCounter <- PrometheusCounter(
                                   namespace,
                                   subsystem,
                                   "sync_downloaded_blocks_total",
                                   "Total blocks downloaded during synchronization",
                                   withExemplars = false,
                                   None
                                 )
    } yield new PrometheusNetworkMetrics(
      processingMessageDurationHistogram,
      handshakedIncomingPeersGauge,
      handshakedOutgoingPeersGauge,
      sentMessagesCounter,
      discoveredPeersSize,
      pendingPeersSize,
      connectionAttempts,
      forkingFactorGauge,
      chainDensityGauge,
      downloadedBlocksCounter
    )
}
