package io.iohk.scevm.exec.metrics

import cats.Applicative
import io.iohk.scevm.metrics.instruments.Gauge

final case class NoOpExecutionMetrics[F[_]: Applicative]() extends ExecutionMetrics[F] {
  override val transactionPoolSize: Gauge[F] = Gauge.noop
}
