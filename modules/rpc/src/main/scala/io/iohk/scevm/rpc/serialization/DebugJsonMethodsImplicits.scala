package io.iohk.scevm.rpc.serialization

import cats.syntax.all._
import io.iohk.scevm.rpc.controllers.DebugController.TraceParameters
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.domain.JsonRpcError.InvalidParams
import org.json4s.JObject

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

object DebugJsonMethodsImplicits extends JsonMethodsImplicits {

  def extractTraceParameters(json: JObject): Either[JsonRpcError, TraceParameters] =
    for {
      tracer <- Either.fromOption(
                  (json \ "tracer").extractOpt[String],
                  InvalidParams("Invalid or missing tracer parameter")
                )
      maybeTimeout <- (json \ "timeout")
                        .extractOpt[String]
                        .traverse(extractDurationFromString)

    } yield TraceParameters(tracer, maybeTimeout)

  protected def extractDurationFromString(input: String): Either[JsonRpcError, FiniteDuration] =
    for {
      duration <- Try(Duration(input)).toEither.left.map(_ => JsonRpcError.InvalidParams())
      finiteDuration <- duration match {
                          case _: Duration.Infinite     => Left(JsonRpcError.InvalidParams())
                          case duration: FiniteDuration => Right(duration)
                        }
    } yield finiteDuration
}
