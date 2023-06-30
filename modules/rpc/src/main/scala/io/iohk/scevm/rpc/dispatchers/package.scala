package io.iohk.scevm.rpc

import cats.MonadThrow
import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import io.iohk.scevm.rpc.domain.JsonRpcError.InternalError
import io.iohk.scevm.rpc.domain.{JsonRpcError, JsonRpcRequest, JsonRpcResponse}
import io.iohk.scevm.rpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder
import io.iohk.scevm.rpc.serialization.{JsonEncoder, JsonMethodDecoder}

package object dispatchers extends StrictLogging {
  final case class EmptyRequest()
  implicit val emptyRequestDecoder: NoParamsMethodDecoder[EmptyRequest] = new NoParamsMethodDecoder(EmptyRequest()) {}

  private def successResponse[T](req: JsonRpcRequest, result: T)(implicit enc: JsonEncoder[T]): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, Some(enc.encodeJson(result)), None, req.id)

  private def errorResponse[T](req: JsonRpcRequest, error: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, None, Some(error), req.id)

  def handleF[F[_]: MonadThrow, Req, Res](
      fn: Req => F[Either[JsonRpcError, Res]],
      rpcReq: JsonRpcRequest
  )(implicit dec: JsonMethodDecoder[Req], enc: JsonEncoder[Res]): F[JsonRpcResponse] =
    dec.decodeJson(rpcReq.params) match {
      case Right(req) =>
        fn(req)
          .map {
            case Right(success) => successResponse(rpcReq, success)
            case Left(error)    => errorResponse(rpcReq, error)
          }
          .recover { case ex =>
            logger.error("Failed to handle RPC request", ex)
            errorResponse(rpcReq, InternalError)
          }
      case Left(error) =>
        errorResponse(rpcReq, error).pure
    }

  def handle[Req, Res](
      fn: Req => IO[Either[JsonRpcError, Res]],
      rpcReq: JsonRpcRequest
  )(implicit dec: JsonMethodDecoder[Req], enc: JsonEncoder[Res]): IO[JsonRpcResponse] =
    handleF[IO, Req, Res](fn, rpcReq)
}
