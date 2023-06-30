package io.iohk.scevm.rpc.router

import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.~>
import io.iohk.armadillo.server._
import io.iohk.armadillo.server.tapir.TapirInterpreter
import io.iohk.armadillo.{JsonRpcRequest, JsonRpcServerEndpoint}
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import org.json4s.JsonAST.JValue
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sttp.tapir.integ.cats._
import sttp.tapir.integ.cats.syntax.ServerEndpointImapK
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.akkahttp.AkkaHttpServerInterpreter

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object ArmadilloAdapter {
  def apply(
      routes: RpcRoutes[IO],
      jsonSupport: JsonSupport[JValue],
      requestTimeout: FiniteDuration,
      overridingEndpoints: List[EndpointOverride[IO]] = List.empty
  ): Route = {
    implicit val value: CatsMonadError[IO] = new CatsMonadError[IO]

    val interceptors: List[Interceptor[IO, JValue]] =
      CustomInterceptors(
        additionalInterceptors = List(
          new TimeoutMethodInterceptor(requestTimeout),
          new RpcCallTrackerMethodInterceptor()
        ),
        serverLog = Some(new RpcServerLog),
        overriddenEndpoints = overridingEndpoints
      ).interceptors

    val tapirInterpreter = new TapirInterpreter(jsonSupport, interceptors)
    val tapirEndpoint: ServerEndpoint[Any, IO] = tapirInterpreter
      .toTapirEndpoint(routes.armadilloEndpoints)
      .getOrElse(throw new RuntimeException("Error during conversion to tapir"))
    toAkkaRoutes(tapirEndpoint :: routes.tapirEndpoints)
  }

  private def toAkkaRoutes(tapirEndpoints: List[ServerEndpoint[Any, IO]]): Route = {
    val ioToFuture = Lambda[IO ~> Future](_.unsafeToFuture())
    val futureToIo = Lambda[Future ~> IO](f => IO.fromFuture(IO.delay(f)))
    AkkaHttpServerInterpreter()(ExecutionContext.global).toRoute(tapirEndpoints.map(_.imapK(ioToFuture)(futureToIo)))
  }

  private class RpcServerLog extends ServerLog[IO, JValue] {
    implicit private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

    override def requestHandled(
        ctx: EndpointHandler.DecodeSuccessContext[IO, _, _, _, JValue],
        response: ServerInterpreter.ResponseHandlingStatus[JValue]
    ): IO[Unit] =
      Logger[IO].debug(s"Request handled: ${ctx.endpoint.endpoint.methodName.asString}")

    override def exception(
        endpoint: JsonRpcServerEndpoint[IO],
        request: JsonRpcRequest[JsonSupport.Json[JValue]],
        e: Throwable
    ): IO[Unit] =
      Logger[IO].error(e)(s"Error during rpc request processing: ${e.getMessage}")

    override def decodeFailure(
        ctx: EndpointHandler.DecodeFailureContext[IO, JValue],
        response: ServerInterpreter.ResponseHandlingStatus[JValue]
    ): IO[Unit] =
      Logger[IO].debug(
        s"Request decoding failed, method: ${ctx.endpoint.endpoint.methodName.asString}, failure: ${ctx.f}"
      )
  }
}
