package io.iohk.scevm.rpc.tapir

import cats.Applicative
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import io.iohk.scevm.utils.BuildInfo
import org.json4s.{DefaultFormats, Extraction, JValue}
import sttp.tapir._
import sttp.tapir.json.json4s._
import sttp.tapir.server.ServerEndpoint.Full

object BuildInfoEndpoint extends JsonMethodsImplicits {

  private val buildInfoEndpoint: Endpoint[Unit, Unit, Unit, JValue, Any] =
    endpoint.get.in("buildinfo").out(jsonBody[JValue])

  private val buildInfoAsJson: JValue = Extraction.decompose(BuildInfo.toMap)(DefaultFormats)

  def serverEndpoint[F[_]: Applicative]: Full[Unit, Unit, Unit, Unit, JValue, Any, F] =
    buildInfoEndpoint.serverLogic(_ => Applicative[F].pure(Right(buildInfoAsJson)))
}
