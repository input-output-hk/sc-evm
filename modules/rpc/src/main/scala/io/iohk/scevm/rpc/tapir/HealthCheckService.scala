package io.iohk.scevm.rpc.tapir

import cats.Applicative
import cats.syntax.all._
import io.iohk.scevm.healthcheck.HealthcheckResponse
import io.iohk.scevm.rpc.armadillo.CommonRpcSchemas.schemaForHealthcheckResponse
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import io.iohk.scevm.utils.NodeStateProvider
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.json4s.jsonBody
import sttp.tapir.server.ServerEndpoint.Full

object HealthCheckService extends JsonMethodsImplicits {
  val healthCheck: String = "healthcheck"

  private val healthCheckEndpoint: Endpoint[Unit, Unit, HealthcheckResponse, HealthcheckResponse, Any] = endpoint.get
    .in(HealthCheckService.healthCheck)
    .errorOut(statusCode(StatusCode.InternalServerError).and(jsonBody[HealthcheckResponse]))
    .out(jsonBody[HealthcheckResponse])

  def serverEndpoint[F[_]: Applicative](
      nodeStateProvider: NodeStateProvider[F]
  ): Full[Unit, Unit, Unit, HealthcheckResponse, HealthcheckResponse, Any, F] =
    healthCheckEndpoint.serverLogic { _ =>
      nodeStateProvider.getNodeState.map(nodeState => Right(HealthcheckResponse(nodeState, List.empty)))
    }
}
