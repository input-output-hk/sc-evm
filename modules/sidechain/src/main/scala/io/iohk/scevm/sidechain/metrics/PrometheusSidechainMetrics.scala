package io.iohk.scevm.sidechain.metrics

import cats.effect.kernel.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.{PrometheusCounter, PrometheusGauge}
import io.iohk.scevm.metrics.instruments.{Counter, Gauge}

final case class PrometheusSidechainMetrics[F[_]](
    override val epochGauge: Gauge[F],
    override val epochPhaseGauge: Gauge[F],
    override val incomingTransactionsCount: Counter[F],
    override val mainchainFollowerSlotLag: Gauge[F],
    override val mainchainFollowerCommitteeNftLag: Gauge[F]
) extends SidechainMetrics[F]

object PrometheusSidechainMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "sidechain"

  def apply[F[_]: Sync](): Resource[F, SidechainMetrics[F]] =
    for {
      epochGauge <- PrometheusGauge(
                      namespace,
                      subsystem,
                      "epoch",
                      "Current sidechain epoch number"
                    )
      epochPhaseGauge <- PrometheusGauge(
                           namespace,
                           subsystem,
                           "epoch_phase",
                           "Current sidechain epoch phase",
                           Some(List("epoch_phase"))
                         )
      incomingTransactionsCount <- PrometheusCounter[F](
                                     namespace,
                                     subsystem,
                                     s"incoming_transaction_count",
                                     s"Count of incoming transaction",
                                     withExemplars = false,
                                     None
                                   )
      slotLagGauge <- PrometheusGauge(
                        namespace,
                        subsystem,
                        "follower_slot_lag",
                        "Number of slots that is missing from cardano follower"
                      )
      handoverLagGauge <- PrometheusGauge(
                            namespace,
                            subsystem,
                            "follower_handover_lag",
                            "Number of handovers that is missing from cardano follower"
                          )
    } yield PrometheusSidechainMetrics(
      epochGauge,
      epochPhaseGauge,
      incomingTransactionsCount,
      slotLagGauge,
      handoverLagGauge
    )
}
