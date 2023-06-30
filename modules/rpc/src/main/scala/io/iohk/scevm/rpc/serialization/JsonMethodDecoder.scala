package io.iohk.scevm.rpc.serialization

import io.iohk.scevm.rpc.domain.JsonRpcError
import org.json4s.JsonAST.JArray

trait JsonMethodDecoder[T] {
  def decodeJson(params: Option[JArray]): Either[JsonRpcError, T]
}
object JsonMethodDecoder {
  class NoParamsMethodDecoder[T](request: => T) extends JsonMethodDecoder[T] {
    def decodeJson(params: Option[JArray]): Either[JsonRpcError, T] =
      params match {
        case None | Some(JArray(Nil)) => Right(request)
        case _                        => Left(JsonRpcError.InvalidParams("No parameters expected"))
      }
  }
}
