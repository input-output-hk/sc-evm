package io.iohk

import io.circe.Json
import io.circe.literal.JsonStringContext

package object jsonRpcClient {

  object JsonRpcRequest {
    def apply(method: String, params: List[io.circe.Json]): Json =
      json"""
      {
        "jsonrpc": "2.0",
        "method": $method,
        "params": $params,
        "id": 1
      }
    """
  }

  final case class JsonRpcResponse[T](jsonrpc: String, result: T, id: Int)
}
