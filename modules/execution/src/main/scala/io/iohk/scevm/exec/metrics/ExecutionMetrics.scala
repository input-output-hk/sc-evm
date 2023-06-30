package io.iohk.scevm.exec.metrics

import io.iohk.scevm.metrics.instruments.Gauge

trait ExecutionMetrics[F[_]] {
  val transactionPoolSize: Gauge[F]
}
