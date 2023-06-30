package io.iohk.scevm.rpc.tapir

import cats.Applicative
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint.Full

class OpenRpcDocumentationEndpoint[F[_]: Applicative](renderingEngineUrl: String, schemaUrl: String) {

  private val redirectUrl: String = renderingEngineUrl + schemaUrl

  private val openRpcDocumentationEndpoint: Endpoint[Unit, Unit, Unit, String, Any] =
    endpoint.get
      .in("openrpc" / "docs")
      .out(statusCode(StatusCode.Found).and(header[String]("Location").description("Redirect address")))

  def serverEndpoint: Full[Unit, Unit, Unit, Unit, String, Any, F] =
    openRpcDocumentationEndpoint.serverLogic(_ => Applicative[F].pure(Right(redirectUrl)))

}
