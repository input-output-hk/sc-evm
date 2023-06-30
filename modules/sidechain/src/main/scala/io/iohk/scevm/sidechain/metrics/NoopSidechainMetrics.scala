package io.iohk.scevm.sidechain.metrics

import cats.Applicative
import io.iohk.scevm.metrics.instruments.{Counter, Gauge}

final case class NoopSidechainMetrics[F[_]: Applicative]() extends SidechainMetrics[F] {
  override val epochGauge: Gauge[F]                       = Gauge.noop[F]
  override val epochPhaseGauge: Gauge[F]                  = Gauge.noop[F]
  override val incomingTransactionsCount: Counter[F]      = Counter.noop[F]
  override val mainchainFollowerSlotLag: Gauge[F]         = Gauge.noop[F]
  override val mainchainFollowerCommitteeNftLag: Gauge[F] = Gauge.noop[F]
}
