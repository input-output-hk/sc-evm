package io.iohk.scevm.rpc.http

import akka.NotUsed
import akka.http.scaladsl.model.{RemoteAddress, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import com.google.common.base.Ticker
import com.google.common.cache.CacheBuilder
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import io.iohk.scevm.rpc.JsonRpcHttpServerConfig.RateLimitConfig
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.serialization.JsonSerializers
import org.json4s.{DefaultFormats, Formats, Serialization, native}

import java.time.Duration

class RateLimit(config: RateLimitConfig, clock: RateLimit.Clock) extends Directive0 with Json4sSupport {

  implicit private val serialization: Serialization = native.Serialization
  implicit private val formats: Formats             = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  private lazy val minInterval = config.minRequestInterval.toSeconds

  private lazy val lru = {
    val nanoDuration = config.minRequestInterval.toNanos
    val javaDuration = Duration.ofNanos(nanoDuration)
    val ticker: Ticker = new Ticker {
      override def read(): Long = clock.getNanoTime
    }
    CacheBuilder
      .newBuilder()
      .weakKeys()
      .expireAfterAccess(javaDuration)
      .ticker(ticker)
      .build[RemoteAddress, NotUsed]()
  }

  private def isBelowRateLimit(ip: RemoteAddress): Boolean = {
    var exists = true
    lru.get(
      ip,
      () => {
        exists = false
        NotUsed
      }
    )
    exists
  }

  // Such algebras prevent if-elseif-else boilerplate in the JsonRPCServer code
  // It is also guaranteed that:
  //   1) no IP address is extracted unless config.enabled is true
  //   2) no LRU is created unless config.enabled is true
  //   3) cache is accessed only once (using get)
  override def tapply(f: Unit => Route): Route =
    if (config.enabled) {
      extractClientIP { ip =>
        if (isBelowRateLimit(ip)) {
          val err = JsonRpcError.RateLimitError(minInterval)
          complete((StatusCodes.TooManyRequests, err))
        } else {
          f.apply(())
        }
      }
    } else f.apply(())

}

object RateLimit {
  trait Clock {
    def getNanoTime: Long
  }
  object Clock {
    object RealTime extends Clock {
      override def getNanoTime: Long = System.nanoTime()
    }
  }
}
