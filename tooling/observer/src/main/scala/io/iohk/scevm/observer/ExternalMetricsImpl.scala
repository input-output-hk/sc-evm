package io.iohk.scevm.observer

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusGauge
import io.iohk.scevm.metrics.instruments.Gauge

trait ExternalMetrics[F[_]] {
  def totalValueLockedStable: Gauge[F]
  def totalValueLockedPending: Gauge[F]
  def totalValueBurned: Gauge[F]
  def burnTransactionsCount: Gauge[F]
  def totalValueMinted: Gauge[F]
  def mintTransactionsCount: Gauge[F]
}

final class PrometheusExternalMetrics[F[_]] private (
    val totalValueLockedStable: PrometheusGauge[F],
    val totalValueLockedPending: PrometheusGauge[F],
    val totalValueBurned: PrometheusGauge[F],
    val burnTransactionsCount: PrometheusGauge[F],
    val totalValueMinted: PrometheusGauge[F],
    val mintTransactionsCount: PrometheusGauge[F]
) extends ExternalMetrics[F]

object PrometheusExternalMetrics {
  def apply[F[_]: Sync]: Resource[F, PrometheusExternalMetrics[F]] =
    for {
      tvlStable <- PrometheusGauge(
                     "observer",
                     "external",
                     "total_value_locked_stable",
                     "Total value locked in the bridge contract (stable)"
                   )
      tvlPending <- PrometheusGauge(
                      "observer",
                      "external",
                      "total_value_locked_pending",
                      "Total value locked in the bridge contract (pending)"
                    )
      totalValueBurned <- PrometheusGauge(
                            "observer",
                            "external",
                            "total_value_burned_pending",
                            "Total value burned on the mainchain (pending)"
                          )
      burnTxCount <- PrometheusGauge(
                       "observer",
                       "external",
                       "burn_transaction_count_pending",
                       "Amount of burn transactions on the mainchain (pending)"
                     )
      totalValueMinted <- PrometheusGauge(
                            "observer",
                            "external",
                            "total_value_minted_pending",
                            "Total value minted on the mainchain (pending)"
                          )
      mintTxCount <- PrometheusGauge(
                       "observer",
                       "external",
                       "mint_transaction_count_pending",
                       "Amount of mint transactions on the mainchain (pending)"
                     )
    } yield new PrometheusExternalMetrics(
      tvlStable,
      tvlPending,
      totalValueBurned,
      burnTxCount,
      totalValueMinted,
      mintTxCount
    )
}
