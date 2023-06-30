package io.iohk.scevm.sidechain

import cats.Show
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import com.github.benmanes.caffeine.cache.Ticker
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import io.iohk.scevm.sidechain.TtlCache.CacheOperation._
import io.iohk.scevm.sidechain.metrics.TtlCacheMetrics
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Time-based cache that caches items for the time specified by the provided `cacheFor` method.
  * Backed by scaffeine. Expired items are evicted by a background process.
  * We decided not to use scala-cache or others because their high-level interfaces don't integrate well into our application
  * @tparam F effect in which the cache operates
  * @tparam K type of the key
  * @tparam V type of the value
  */
class TtlCache[F[_]: Sync, K: Show, V: Show](
    cache: Cache[K, V],
    semaphore: Semaphore[F],
    dataProvider: K => F[V],
    metrics: TtlCacheMetrics[F]
) {
  def apply(key: K)(implicit log: Logger[F]): F[V] = {
    val concurrentAccessMetric =
      Resource.make(metrics.semaphoreAcquire.inc)(_ => metrics.semaphoreRelease.inc)
    (concurrentAccessMetric >> semaphore.permit)
      .use { _ =>
        for {
          _ <- Logger[F].debug(show"Trying to get cached value for $key")
          result <- Sync[F].delay(cache.getIfPresent(key)).flatMap {
                      case Some(cachedValue) =>
                        handleCacheHit(key, cachedValue)
                      case _ =>
                        handleCacheMiss(key)
                    }
        } yield result
      }
  }

  private def handleCacheHit(key: K, cachedValue: V)(implicit
      log: Logger[F]
  ) =
    Logger[F].debug(show"Found cached value for $key") >>
      metrics.cacheHit.inc >>
      Sync[F]
        .delay(cache.estimatedSize())
        .flatMap(s => metrics.cacheSize.set(s))
        .as(cachedValue)

  private def handleCacheMiss(key: K)(implicit log: Logger[F]) =
    Logger[F].debug(show"Didn't find cached value for $key") >>
      metrics.cacheMiss.inc >>
      dataProvider(key)
        .flatTap { data =>
          Sync[F].delay(cache.put(key, data)) >>
            Logger[F].trace(show"Inserted new key $key with value $data") >>
            Sync[F]
              .delay(cache.estimatedSize())
              .flatMap(s => metrics.cacheSize.set(s))
        }
}

object TtlCache {
  def wrap[F[_]: Async, K: Show, V: Show](
      cacheSize: Long,
      metrics: TtlCacheMetrics[F],
      dataProvider: K => F[V],
      cacheFor: PartialFunction[CacheOperation[V], FiniteDuration],
      ticker: Ticker = Ticker.systemTicker()
  ): F[TtlCache[F, K, V]] =
    for {
      semaphore <- Semaphore[F](1)
      cache = Scaffeine()
                .ticker(ticker)
                .maximumSize(maximumSize = cacheSize)
                .expireAfter[K, V](
                  create = (_, v) => cacheFor.lift(Create(v)).getOrElse(0.seconds),
                  update = (_, v, _) => cacheFor.lift(Update(v)).getOrElse(0.seconds),
                  read = (_, v, _) => cacheFor.lift(Read(v)).getOrElse(0.seconds)
                )
                .build[K, V]()
    } yield new TtlCache[F, K, V](cache, semaphore, dataProvider, metrics)

  sealed trait CacheOperation[V]
  object CacheOperation {
    final case class Create[V](v: V) extends CacheOperation[V]
    final case class Read[V](v: V)   extends CacheOperation[V]
    final case class Update[V](v: V) extends CacheOperation[V]
  }
}
