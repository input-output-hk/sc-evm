package io.iohk.scevm.sidechain.metrics

import io.iohk.scevm.metrics.instruments.{Counter, Gauge}

trait SidechainMetrics[F[_]] {
  def epochGauge: Gauge[F]

  def epochPhaseGauge: Gauge[F]

  def incomingTransactionsCount: Counter[F]

  /** Measure the difference between the current mainchain slot, and the latest mainchain slot available in the mainchain follower */
  def mainchainFollowerSlotLag: Gauge[F]

  def mainchainFollowerCommitteeNftLag: Gauge[F]
}
