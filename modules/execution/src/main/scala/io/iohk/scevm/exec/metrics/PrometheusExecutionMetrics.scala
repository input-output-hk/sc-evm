package io.iohk.scevm.exec.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusGauge
import io.iohk.scevm.metrics.instruments.Gauge

final class PrometheusExecutionMetrics[F[_]] private (txPoolSize: PrometheusGauge[F]) extends ExecutionMetrics[F] {
  override val transactionPoolSize: Gauge[F] = txPoolSize
}

object PrometheusExecutionMetrics {
  private val namespace = "sc_evm"
  def apply[F[_]: Sync]: Resource[F, PrometheusExecutionMetrics[F]] =
    for {
      txPoolSize <-
        PrometheusGauge(namespace, "execution", "transaction_pool_size", "Amount of transactions in the pool")
    } yield new PrometheusExecutionMetrics(txPoolSize)
}
