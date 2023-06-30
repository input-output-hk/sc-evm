package io.iohk.scevm.network.metrics
import cats.effect.Sync
import io.iohk.scevm.metrics.instruments.{Counter, Gauge, Histogram}

final case class NoOpNetworkMetrics[F[_]: Sync]() extends NetworkMetrics[F] {
  override val processingMessageDurationHistogram: Histogram[F] = Histogram.noop
  override val handshakedIncomingPeersGauge: Gauge[F]           = Gauge.noop
  override val handshakedOutgoingPeersGauge: Gauge[F]           = Gauge.noop
  override val sentMessagesCounter: Counter[F]                  = Counter.noop
  override val discoveredPeersSize: Gauge[F]                    = Gauge.noop
  override val pendingPeersSize: Gauge[F]                       = Gauge.noop
  override val connectionAttempts: Counter[F]                   = Counter.noop
  override val forkingFactorGauge: Gauge[F]                     = Gauge.noop
  override val chainDensityGauge: Gauge[F]                      = Gauge.noop
  override val syncDownloadedBlocksCounter: Counter[F]          = Counter.noop
}
