package io.iohk.scevm.network.metrics

import io.iohk.scevm.metrics.instruments.{Counter, Gauge, Histogram}

trait NetworkMetrics[F[_]] extends ConsensusNetworkMetrics[F] {
  val processingMessageDurationHistogram: Histogram[F]
  val handshakedIncomingPeersGauge: Gauge[F]
  val handshakedOutgoingPeersGauge: Gauge[F]
  val sentMessagesCounter: Counter[F]
  val discoveredPeersSize: Gauge[F]
  val pendingPeersSize: Gauge[F]
  val connectionAttempts: Counter[F]
  val syncDownloadedBlocksCounter: Counter[F]
}
