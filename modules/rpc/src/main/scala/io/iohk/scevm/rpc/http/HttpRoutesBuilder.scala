package io.iohk.scevm.rpc.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler, Route}
import ch.megard.akka.http.cors.javadsl.CorsRejection
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.model.HttpOriginMatcher
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import io.iohk.scevm.rpc.JsonRpcHttpServerConfig
import io.iohk.scevm.rpc.domain.{JsonRpcError, JsonRpcResponse}
import io.iohk.scevm.rpc.serialization.JsonSerializers
import org.json4s.{DefaultFormats, Formats, Serialization, native}

class HttpRoutesBuilder(corsAllowedOrigins: HttpOriginMatcher, appRoutes: () => Route, rateLimit: RateLimit)
    extends Json4sSupport {

  implicit private val serialization: Serialization = native.Serialization
  implicit private val formats: Formats             = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  private val corsSettings: CorsSettings = CorsSettings.defaultSettings
    .withAllowGenericHttpRequests(true)
    .withAllowedOrigins(corsAllowedOrigins)

  implicit private def myRejectionHandler: RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle {
        case _: MalformedRequestContentRejection =>
          complete((StatusCodes.BadRequest, JsonRpcResponse("2.0", None, Some(JsonRpcError.ParseError), None)))
        case _: CorsRejection =>
          complete(StatusCodes.Forbidden)
      }
      .result()

  val routes: Route = Route.seal(cors(corsSettings) {
    rateLimit(appRoutes())
  })
}

object HttpRoutesBuilder {
  def apply(config: JsonRpcHttpServerConfig, appRoutes: () => Route): Route = new HttpRoutesBuilder(
    config.corsAllowedOrigins,
    appRoutes,
    new RateLimit(config.rateLimit, RateLimit.Clock.RealTime)
  ).routes
}
