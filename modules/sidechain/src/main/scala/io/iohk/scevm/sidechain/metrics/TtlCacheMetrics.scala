package io.iohk.scevm.sidechain.metrics

import io.iohk.scevm.metrics.instruments.{Counter, Gauge}

trait TtlCacheMetrics[F[_]] {
  val cacheHit: Counter[F]
  val cacheMiss: Counter[F]
  val semaphoreAcquire: Counter[F]
  val semaphoreRelease: Counter[F]
  val cacheSize: Gauge[F]
}
