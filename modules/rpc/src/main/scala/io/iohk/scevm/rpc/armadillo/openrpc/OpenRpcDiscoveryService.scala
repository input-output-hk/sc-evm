package io.iohk.scevm.rpc.armadillo.openrpc

import cats.Applicative
import io.circe.Printer
import io.circe.syntax.EncoderOps
import io.iohk.armadillo._
import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.openrpc.OpenRpcDocsInterpreter
import io.iohk.armadillo.openrpc.circe._
import io.iohk.armadillo.openrpc.model.OpenRpcInfo
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.utils.BuildInfo
import org.json4s.JValue
import org.json4s.native.JsonMethods

object OpenRpcDiscoveryService extends CommonApi {

  val serviceDiscovery: String = "rpc.discover"

  private val rpcDiscoverEndpoint: JsonRpcEndpoint[Unit, Unit, JValue] =
    jsonRpcEndpoint(new MethodName(serviceDiscovery))
      .out[JValue]("schema")

  def serverEndpoint[F[_]: Applicative](
      endpoints: List[JsonRpcEndpoint[_, _, _]]
  ): JsonRpcServerEndpoint.Full[Unit, Unit, JValue, F] =
    rpcDiscoverEndpoint.serverLogic[F] { _ =>
      val specification = OpenRpcDocsInterpreter().toOpenRpc(
        OpenRpcInfo(BuildInfo.version, "RPC specification"),
        endpoints
      )

      val circeJson  = specification.asJson
      val json4sJson = JsonMethods.parse(circeJson.printWith(Printer(dropNullValues = true, indent = "")))
      Applicative[F].pure(Right(json4sJson))
    }
}
