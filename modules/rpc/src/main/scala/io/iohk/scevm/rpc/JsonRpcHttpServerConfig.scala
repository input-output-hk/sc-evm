package io.iohk.scevm.rpc

import akka.http.scaladsl.model.headers.HttpOrigin
import ch.megard.akka.http.cors.scaladsl.model.HttpOriginMatcher
import com.typesafe.config.Config
import io.iohk.scevm.rpc.JsonRpcHttpServerConfig.RateLimitConfig

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.Try

final case class JsonRpcHttpServerConfig(
    enabled: Boolean,
    interface: String,
    port: Int,
    corsAllowedOrigins: HttpOriginMatcher,
    rateLimit: RateLimitConfig,
    requestTimeout: FiniteDuration,
    openrpcSpecificationEnabled: Boolean,
    openrpcRenderingEngineUrl: String,
    openrpcSchemaUrl: String
)

object JsonRpcHttpServerConfig {

  final case class RateLimitConfig(
      enabled: Boolean,
      minRequestInterval: FiniteDuration
  )

  def fromConfig(config: Config): JsonRpcHttpServerConfig = {
    val rpcHttpConfig = config.getConfig("network.rpc.http")
    JsonRpcHttpServerConfig(
      enabled = rpcHttpConfig.getBoolean("enabled"),
      interface = rpcHttpConfig.getString("interface"),
      port = rpcHttpConfig.getInt("port"),
      corsAllowedOrigins = parseCorsAllowedOrigins(rpcHttpConfig),
      rateLimit = rateLimitConfig(rpcHttpConfig.getConfig("rate-limit")),
      requestTimeout = rpcHttpConfig.getDuration("request-timeout").toScala,
      openrpcSpecificationEnabled = rpcHttpConfig.getBoolean("openrpc-specification-enabled"),
      openrpcRenderingEngineUrl = rpcHttpConfig.getString("openrpc-rendering-engine-url"),
      openrpcSchemaUrl = rpcHttpConfig.getString("openrpc-schema-url")
    )
  }

  private def rateLimitConfig(rateLimitConfig: Config): RateLimitConfig =
    RateLimitConfig(
      rateLimitConfig.getBoolean("enabled"),
      rateLimitConfig.getDuration("min-request-interval").toScala
    )

  private def parseCorsAllowedOrigins(config: Config): HttpOriginMatcher = {
    val key = "cors-allowed-origins"
    Try(parseMultipleOrigins(config.getStringList(key).asScala.toSeq)).recoverWith { case _ =>
      Try(parseSingleOrigin(config.getString(key)))
    }.get
  }

  private def parseMultipleOrigins(origins: Seq[String]): HttpOriginMatcher = HttpOriginMatcher(
    origins.map(HttpOrigin(_)): _*
  )

  private def parseSingleOrigin(origin: String): HttpOriginMatcher = origin match {
    case "*" => HttpOriginMatcher.*
    case s   => HttpOriginMatcher.Default(HttpOrigin(s) :: Nil)
  }
}
