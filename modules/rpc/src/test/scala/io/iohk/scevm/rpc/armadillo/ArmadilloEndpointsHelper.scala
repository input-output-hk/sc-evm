package io.iohk.scevm.rpc.armadillo

import akka.actor.ActorSystem
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import com.softwaremill.diffx.{Diff, ObjectMatcher}
import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.armadillo
import io.iohk.armadillo.json.json4s
import io.iohk.armadillo.json.json4s.Json4sSupport
import io.iohk.armadillo.server.ServerInterpreter
import io.iohk.armadillo.{JsonRpcError, JsonRpcErrorResponse, JsonRpcSuccessResponse}
import io.iohk.scevm.rpc.JsonHelper
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import org.json4s.JsonAST._
import org.scalactic.source.Position
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.{Assertion, EitherValues}
import sttp.client3.{HttpError, Request, Response, ResponseException}

import scala.annotation.unused
import scala.concurrent.duration.DurationInt

trait ArmadilloEndpointsHelper
    extends AsyncWordSpec
    with Matchers
    with DiffShouldMatcher
    with ScalatestRouteTest
    with JsonMethodsImplicits
    with EitherValues
    with JsonHelper {

  implicit protected val json4sSupport: Json4sSupport = json4s.Json4sSupport(
    org.json4s.native.parseJson[String](_),
    j => org.json4s.native.compactJson(org.json4s.native.renderJValue(j))
  )

  implicit def default(implicit @unused system: ActorSystem): RouteTestTimeout = RouteTestTimeout(3.seconds)

  val JsonRpc: String         = "2.0"
  val RawId: Int              = 1
  val InvalidParamsJson: Json = json"""{"code": -32602, "message": "Invalid params"}"""

  val InvalidParamsError: JsonRpcError[Unit] =
    JsonRpcError(ServerInterpreter.InvalidParams.code, ServerInterpreter.InvalidParams.message, None)

  def jsonRequest(methodName: String, params: JValue): JValue =
    JObject("jsonrpc" -> JString(JsonRpc), "method" -> JString(methodName), "params" -> params, "id" -> JInt(RawId))

  type AAA          = Either[ResponseException[JsonRpcErrorResponse[JValue], Exception], JsonRpcSuccessResponse[JValue]]
  type ResponseType = Response[AAA]

  implicit val objectFieldMatcher: _root_.com.softwaremill.diffx.SeqMatcher[(String, JValue)] =
    ObjectMatcher.seq[(String, JValue)].byValue(_._1)
  implicit val diffJValue: Diff[JValue] = Diff.derived

  def assertResponse[T <: armadillo.JsonRpcResponse[JValue]](expected: Json)(r: ResponseType)(implicit
      pos: Position
  ): Assertion =
    r.body match {
      case Right(JsonRpcSuccessResponse(_, result, _)) =>
        result shouldMatchTo circeToJson4s(expected)

      case Left(HttpError(JsonRpcErrorResponse(_, error, _), statusCode)) =>
        fail(s"expected success but got: $error, $statusCode")

      case other =>
        fail(s"unexpected state: $other")
    }

  def assertError(expected: Json)(r: ResponseType): Assertion =
    r.body match {
      case Right(JsonRpcSuccessResponse(_, result, _)) =>
        fail(s"expected error but got: $result")
      case Left(HttpError(JsonRpcErrorResponse(_, error, _), statusCode)) =>
        assert(statusCode.isClientError)
        error shouldMatchTo circeToJson4s(expected)
      case other =>
        fail(s"unexpected state: $other")
    }

  def sttpRequest(json: JValue): Request[AAA, Any] = {
    import sttp.client3.json4s._
    import sttp.model.Uri._
    sttp.client3.basicRequest
      .post(uri"http://localhost:7654")
      .body(json)
      .response(asJsonEither[JsonRpcErrorResponse[JValue], JsonRpcSuccessResponse[JValue]])
  }
}
