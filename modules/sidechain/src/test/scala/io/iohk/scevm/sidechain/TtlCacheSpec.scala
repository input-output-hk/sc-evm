package io.iohk.scevm.sidechain

import cats.effect.{IO, Ref}
import cats.syntax.all._
import com.github.benmanes.caffeine.cache.Ticker
import io.iohk.scevm.sidechain.TtlCache.CacheOperation
import io.iohk.scevm.sidechain.metrics.NoopTtlCacheMetrics
import io.iohk.scevm.testing.IOSupport
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** This test is not based on cats.effect.testkit.TestControl because scaffeine Ticker has a synchronous
  * interface preventing from implementing it via cats.Clock
  */
class TtlCacheSpec extends AsyncFreeSpec with Matchers with IOSupport with ScalaFutures with IntegrationPatience {
  implicit private val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  val Ttl: FiniteDuration                                    = 1.minute

  "should forward the call to the underlying data provider" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      ticker       = new FakeTicker()
      r <-
        program(callCounter, ticker).flatMap(_.apply(1))
    } yield r shouldBe 1).ioValue
  }

  "should call dataProvider for each distinct key" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      ticker       = new FakeTicker()
      r <- program(callCounter, ticker).flatMap { cache =>
             cache(1) >> cache(2) >> cache(3) >> cache(4)
           }
    } yield r shouldBe 4).ioValue
  }

  "should return cached data" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      ticker       = new FakeTicker()
      r           <- program(callCounter, ticker).flatMap(cache => cache(1) >> cache(1))
    } yield r shouldBe 1).ioValue
  }

  "should fetch the data again when cached data expired" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      waitDuration = Ttl + 1.second
      ticker       = new FakeTicker()
      r <-
        program(callCounter, ticker).flatMap { cache =>
          cache(1) >> ticker.advanceTimeBy(waitDuration) >> cache(1)
        }
    } yield r shouldBe 2).ioValue
  }

  "should allow certain type of data not to be cached" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      ticker       = new FakeTicker()
      r <-
        program(callCounter, ticker, PartialFunction.empty).flatMap { cache =>
          cache(1) >> cache(1)
        }
    } yield r shouldBe 2).ioValue
  }

  "should restore original ttl each time a cache entry is hit" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      waitDuration = Ttl * 2 / 3 // more than half of ttl
      ticker       = new FakeTicker()
      r <-
        for {
          cache  <- program(callCounter, ticker, _ => Ttl)
          _      <- cache(1) // put value into the cache
          _      <- ticker.advanceTimeBy(waitDuration)
          _      <- cache(1) // restore ttl
          _      <- ticker.advanceTimeBy(waitDuration)
          _      <- cache(1) // get value from the cache
          _      <- ticker.advanceTimeBy(Ttl + 1.second) // wait for eviction
          result <- cache(1)
        } yield result
    } yield r shouldBe 2).ioValue
  }

  "should allow not restoring original ttl for certain type of data when a cache entry is hit" in {
    (for {
      callCounter <- Ref.of[IO, Int](0)
      waitDuration = Ttl * 2 / 3 // more than half of ttl
      ticker       = new FakeTicker()
      r <-
        for {
          cache <- program(
                     callCounter,
                     new FakeTicker(),
                     {
                       case CacheOperation.Create(_) => Ttl
                       case CacheOperation.Read(_)   => 0.seconds
                     }
                   )
          _      <- cache(1) // put value into the cache
          _      <- ticker.advanceTimeBy(waitDuration)
          _      <- cache(1) // do not restore ttl
          _      <- ticker.advanceTimeBy(waitDuration) // value gets evicted
          result <- cache(1)
        } yield result
    } yield r shouldBe 2).ioValue
  }

  private def program(
      callCounter: Ref[IO, Int],
      ticker: Ticker,
      cacheFor: PartialFunction[CacheOperation[Int], FiniteDuration] = _ => Ttl,
      cacheSize: Int = 10
  ) =
    for {
      cache <- TtlCache.wrap(
                 cacheSize,
                 new NoopTtlCacheMetrics[IO](),
                 (_: Int) => callCounter.updateAndGet(_ + 1),
                 cacheFor,
                 ticker
               )
    } yield cache

  class FakeTicker(var currentTime: Long = 0L) extends Ticker {
    override def read(): Long = currentTime

    def advanceTimeBy(d: FiniteDuration): IO[Unit] = IO.delay {
      currentTime = currentTime + d.toNanos
      ()
    }
  }
}
