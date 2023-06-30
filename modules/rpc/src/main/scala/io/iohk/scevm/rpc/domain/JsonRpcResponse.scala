package io.iohk.scevm.rpc.domain

import io.iohk.scevm.rpc.domain.JsonRpcRequest.RequestId
import org.json4s.JsonAST.JValue
import org.json4s.native.Serialization.write
import org.json4s.{DefaultFormats, Formats}

final case class JsonRpcResponse(jsonrpc: String, result: Option[JValue], error: Option[JsonRpcError], id: RequestId) {
  def stringify: String = {
    implicit val formats: Formats = DefaultFormats
    "JsonRpcResponse" + (jsonrpc, result.map(write(_)), error.map(write(_))).toString
  }
}
