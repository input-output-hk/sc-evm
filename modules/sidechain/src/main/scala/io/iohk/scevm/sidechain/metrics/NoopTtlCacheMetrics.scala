package io.iohk.scevm.sidechain.metrics

import cats.Applicative
import io.iohk.scevm.metrics.instruments.{Counter, Gauge}

final case class NoopTtlCacheMetrics[F[_]: Applicative]() extends TtlCacheMetrics[F] {
  override val cacheHit: Counter[F]         = Counter.noop
  override val cacheMiss: Counter[F]        = Counter.noop
  override val cacheSize: Gauge[F]          = Gauge.noop
  override val semaphoreAcquire: Counter[F] = Counter.noop
  override val semaphoreRelease: Counter[F] = Counter.noop
}
