package io.iohk.scevm.rpc.domain

import io.iohk.scevm.rpc.domain.JsonRpcRequest.RequestId
import org.json4s.JsonAST.JArray
import org.json4s.native.Serialization.write
import org.json4s.{DefaultFormats, Formats}

//TODO: work on a more elegant solution
trait SensitiveInformationToString {
  val method: String

  def toStringWithSensitiveInformation: String =
    if (!method.contains("personal"))
      toString
    else "sensitive information"
}

final case class JsonRpcRequest(jsonrpc: String, method: String, params: Option[JArray], id: RequestId)
    extends SensitiveInformationToString {

  def stringify: String = {
    implicit val formats: Formats = DefaultFormats
    "JsonRpcRequest" + (jsonrpc, method, params.map(write(_)), id.map(write(_))).toString
  }
}

object JsonRpcRequest {
  type RequestId = Option[Either[Int, String]]
  def toId(int: Int): RequestId       = Some(Left(int))
  def toId(string: String): RequestId = Some(Right(string))
  val nullId: RequestId               = None
}
