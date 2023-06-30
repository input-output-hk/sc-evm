package io.iohk.scevm.sidechain.metrics
import cats.effect.kernel.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.{PrometheusCounter, PrometheusGauge}
import io.iohk.scevm.metrics.instruments.{Counter, Gauge}

final case class PrometheusTtlCacheMetrics[F[_]](
    override val cacheMiss: Counter[F],
    override val cacheHit: Counter[F],
    override val cacheSize: Gauge[F],
    override val semaphoreAcquire: Counter[F],
    override val semaphoreRelease: Counter[F]
) extends TtlCacheMetrics[F]

object PrometheusTtlCacheMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "core"

  // scalastyle:off method.length
  def apply[F[_]: Sync](cacheName: String): Resource[F, PrometheusTtlCacheMetrics[F]] =
    for {
      slotLeaderCacheHit <-
        PrometheusCounter[F](
          namespace,
          subsystem,
          s"${cacheName}_cache_hit_counter",
          s"Total $cacheName cache hits",
          withExemplars = false,
          None
        )
      slotLeaderCacheMiss <-
        PrometheusCounter[F](
          namespace,
          subsystem,
          s"${cacheName}_cache_miss_counter",
          s"Total $cacheName cache miss",
          withExemplars = false,
          None
        )
      semaphoreAcquire <-
        PrometheusCounter[F](
          namespace,
          subsystem,
          s"${cacheName}_semaphore_acquire_counter",
          s"Total amount of times the cache $cacheName semaphore was acquired",
          withExemplars = false,
          None
        )
      semaphoreRelease <-
        PrometheusCounter[F](
          namespace,
          subsystem,
          s"${cacheName}_semaphore_release_counter",
          s"Total amount of times the cache $cacheName semaphore was released",
          withExemplars = false,
          None
        )
      size <-
        PrometheusGauge[F](
          namespace,
          subsystem,
          s"${cacheName}_cache_size",
          s"Size of the $cacheName cache"
        )
    } yield new PrometheusTtlCacheMetrics[F](
      cacheMiss = slotLeaderCacheMiss,
      cacheHit = slotLeaderCacheHit,
      cacheSize = size,
      semaphoreAcquire = semaphoreAcquire,
      semaphoreRelease = semaphoreRelease
    )
  // scalastyle:on method.length
}
