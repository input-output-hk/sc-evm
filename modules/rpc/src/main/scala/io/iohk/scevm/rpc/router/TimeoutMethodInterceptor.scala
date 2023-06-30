package io.iohk.scevm.rpc.router

import cats.effect.IO
import io.iohk.armadillo.server.ServerInterpreter.{ResponseHandlingStatus, ServerResponse}
import io.iohk.armadillo.server._
import io.iohk.armadillo.{JsonRpcId, JsonRpcResponse => ArmadilloJsonRpcResponse}
import io.iohk.scevm.rpc.domain.{JsonRpcError, JsonRpcStandardErrorCodes}
import io.iohk.scevm.rpc.serialization.ArmadilloJsonSupport.jsonSupport
import org.json4s.JsonAST.JValue
import sttp.monad.MonadError

import scala.concurrent.duration.FiniteDuration

class TimeoutMethodInterceptor(timeout: FiniteDuration) extends MethodInterceptor[IO, JValue] {

  override def apply(
      responder: Responder[IO, JValue],
      jsonSupport: JsonSupport[JValue],
      methodHandler: EndpointInterceptor[IO, JValue] => MethodHandler[IO, JValue]
  ): MethodHandler[IO, JValue] = new MethodHandler[IO, JValue] {
    val next: MethodHandler[IO, JValue] = methodHandler(EndpointInterceptor.noop)
    override def onDecodeSuccess[I](ctx: MethodHandler.DecodeSuccessContext[IO, JValue])(implicit
        monad: MonadError[IO]
    ): IO[ResponseHandlingStatus[JValue]] = handleTimeout(ctx.request.id, next.onDecodeSuccess(ctx))

    override def onDecodeFailure(ctx: MethodHandler.DecodeFailureContext[IO, JValue])(implicit
        monad: MonadError[IO]
    ): IO[ResponseHandlingStatus[JValue]] = handleTimeout(None, next.onDecodeFailure(ctx))
  }

  private def handleTimeout(
      requestId: Option[JsonRpcId],
      program: IO[ResponseHandlingStatus[JValue]]
  ): IO[ResponseHandlingStatus[JValue]] = {
    val timeoutIO = IO.sleep(timeout)

    IO.race[Unit, ResponseHandlingStatus[JValue]](timeoutIO, program).map {
      case Left(_)      => ResponseHandlingStatus.Handled(Some(createTimeoutResponse(requestId)))
      case Right(value) => value
    }
  }

  private def createTimeoutResponse(requestId: Option[JsonRpcId]): ServerResponse[JValue] = {
    val json = jsonSupport.encodeResponse(
      ArmadilloJsonRpcResponse.error_v2(
        JsonRpcError.jsonRpcErrorEncoder.encodeJson(
          JsonRpcError(
            JsonRpcStandardErrorCodes.InternalError,
            "The server was not able to produce a timely response to your request.",
            None
          )
        ),
        requestId
      )
    )

    ServerResponse.Failure(json)
  }

}
