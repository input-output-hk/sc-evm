package io.iohk.scevm.rpc.router

import cats.effect.IO
import io.iohk.armadillo.server.ServerInterpreter.{ResponseHandlingStatus, ServerResponse}
import io.iohk.armadillo.server._
import org.json4s.JsonAST.JValue
import sttp.monad.MonadError

import java.time.Duration

class RpcCallTrackerMethodInterceptor() extends MethodInterceptor[IO, JValue] {
  override def apply(
      responder: Responder[IO, JValue],
      jsonSupport: JsonSupport[JValue],
      methodHandler: EndpointInterceptor[IO, JValue] => MethodHandler[IO, JValue]
  ): MethodHandler[IO, JValue] = {
    val next: MethodHandler[IO, JValue] = methodHandler(EndpointInterceptor.noop)

    new MethodHandler[IO, JValue] {
      override def onDecodeSuccess[I](ctx: MethodHandler.DecodeSuccessContext[IO, JValue])(implicit
          monad: MonadError[IO]
      ): IO[ServerInterpreter.ResponseHandlingStatus[JValue]] = {
        val startTime = System.nanoTime()
        for {
          result <- next.onDecodeSuccess(ctx).flatTap {
                      case ResponseHandlingStatus.Handled(value) =>
                        value match {
                          case Some(ServerResponse.Success(_)) =>
                            IO.delay(JsonRpcControllerMetrics.MethodsSuccessCounter.inc())
                          case Some(ServerResponse.Failure(_)) =>
                            IO.delay(JsonRpcControllerMetrics.MethodsErrorCounter.inc())
                          case Some(ServerResponse.ServerFailure(_)) =>
                            IO.delay(JsonRpcControllerMetrics.MethodsExceptionCounter.inc())
                          case None => IO.unit // Notification request, we can't know if there is an issue or not
                        }
                      case ResponseHandlingStatus.Unhandled =>
                        IO.delay(JsonRpcControllerMetrics.NotFoundMethodsCounter.inc())
                    }
          endTime  = System.nanoTime()
          duration = Duration.ofNanos(endTime - startTime)
          _       <- IO.delay(JsonRpcControllerMetrics.recordMethodProcessingDuration(ctx.request.method, duration))
        } yield result
      }

      override def onDecodeFailure(ctx: MethodHandler.DecodeFailureContext[IO, JValue])(implicit
          monad: MonadError[IO]
      ): IO[ServerInterpreter.ResponseHandlingStatus[JValue]] = next.onDecodeFailure(ctx)
    }
  }
}
