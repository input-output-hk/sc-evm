package io.iohk.scevm.rpc.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import ch.megard.akka.http.cors.scaladsl.model.HttpOriginMatcher
import io.iohk.armadillo.JsonRpcServerEndpoint.Full
import io.iohk.armadillo.json.json4s.jsonRpcCodec
import io.iohk.armadillo.{MethodNameContext, jsonRpcEndpoint}
import io.iohk.bytes.ByteString
import io.iohk.scevm.rpc.JsonRpcHttpServerConfig.RateLimitConfig
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import io.iohk.scevm.rpc.domain.JsonRpcRequest.RequestId
import io.iohk.scevm.rpc.domain._
import io.iohk.scevm.rpc.router.ArmadilloAdapter
import io.iohk.scevm.rpc.serialization._
import org.json4s.JString
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class HttpRoutesBuilderSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  import HttpRoutesBuilderSpec._

  it should "return BadRequest when malformed request is received" in new TestSetup {
    val jsonRequestInvalid: ByteString = ByteString("""{"jsonrpc":"2.0", "method": "this is not a valid json""")
    val postRequest: HttpRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequestInvalid))

    postRequest ~> Route.seal(mockJsonRpcHttpServer) ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "return a CORS Error" in new TestSetup {
    val postRequest: HttpRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/",
      headers = Origin(HttpOrigin("http://non_accepted_origin.com")) :: Nil,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    )

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithCors) ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "return too many requests error with ip-restriction enabled and two requests executed in a row" in new TestSetup {
    val postRequest: HttpRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = HttpRoutesBuilderSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(JString("Hello"))
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual id
    }
    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit) ~> check {
      status shouldEqual StatusCodes.TooManyRequests
    }
  }

  trait TestSetup extends MockFactory {
    val jsonRpc                 = "2.0"
    val rawId                   = 1
    val id: RequestId           = JsonRpcRequest.toId(rawId)
    val jsonRequest: ByteString = ByteString(s"""{"jsonrpc":"$jsonRpc", "method": "test", "id": "$rawId"}""")

    val rateLimitConfig: RateLimitConfig = RateLimitConfig(
      enabled = false,
      minRequestInterval = FiniteDuration.apply(20, TimeUnit.MILLISECONDS)
    )

    val requestTimeout: FiniteDuration = 5.seconds

    val testServerEndpoint: Full[Unit, Unit, String, IO] = jsonRpcEndpoint(m"test")
      .out[String]("message")
      .serverLogic[IO](_ => IO.pure("Hello".asRight))

    val appRoutes: Route = ArmadilloAdapter(
      RpcRoutes.empty.withArmadilloEndpoints(List(testServerEndpoint)),
      ArmadilloJsonSupport.jsonSupport,
      requestTimeout
    )

    val mockJsonRpcHttpServer: Route =
      new HttpRoutesBuilder(
        corsAllowedOrigins = HttpOriginMatcher.*,
        rateLimit = new RateLimit(rateLimitConfig, RateLimit.Clock.RealTime),
        appRoutes = () => appRoutes
      ).routes

    val corsAllowedOrigin: HttpOrigin = HttpOrigin("http://localhost:3333")
    val mockJsonRpcHttpServerWithCors: Route =
      new HttpRoutesBuilder(
        corsAllowedOrigins = HttpOriginMatcher(corsAllowedOrigin),
        rateLimit = new RateLimit(rateLimitConfig, RateLimit.Clock.RealTime),
        appRoutes = () => appRoutes
      ).routes

    val testClock = new TestClock()
    val mockJsonRpcHttpServerWithRateLimit: Route =
      new HttpRoutesBuilder(
        corsAllowedOrigins = HttpOriginMatcher.*,
        rateLimit = new RateLimit(rateLimitConfig.copy(enabled = true), testClock),
        appRoutes = () => appRoutes
      ).routes
  }
}

object HttpRoutesBuilderSpec extends JsonMethodsImplicits {

  import org.json4s._
  import org.json4s.native.JsonMethods._

  private def parserJsonRpcError(jsonRpcError: JValue): JsonRpcError = {
    val code    = (jsonRpcError \ "code").extract[Int]
    val message = (jsonRpcError \ "message").extract[String]
    val data    = (jsonRpcError \ "data").toOption.map(_.extract[JValue])
    JsonRpcError(code = code, message = message, data = data)
  }

  private def parserJsonRequestId(id: JValue): RequestId =
    id match {
      case JsonAST.JNull      => JsonRpcRequest.nullId
      case JsonAST.JString(s) => JsonRpcRequest.toId(s)
      case JsonAST.JInt(num)  => JsonRpcRequest.toId(num.toInt)
      case _                  => throw new RuntimeException("request-id should be null, int or string")
    }

  private def parser(jsonRpcResponse: String): JsonRpcResponse = {
    val jsValue = parse(jsonRpcResponse)
    val jsonRpc = (jsValue \ "jsonrpc").extract[String]
    val result  = (jsValue \ "result").toOption.map(_.extract[JValue])
    val error   = (jsValue \ "error").toOption.map(parserJsonRpcError)
    val id      = parserJsonRequestId(jsValue \ "id")
    JsonRpcResponse(jsonrpc = jsonRpc, result = result, error = error, id = id)
  }

  class TestClock extends RateLimit.Clock {
    var time                       = 0L
    override def getNanoTime: Long = time
  }

}
