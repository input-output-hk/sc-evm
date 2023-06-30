package io.iohk.scevm.rpc.armadillo.eth

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.rpc.armadillo.CommonApi

object EthInfoApi extends CommonApi {

  val eth_chainId: JsonRpcEndpoint[Unit, JsonRpcError[Unit], Byte] =
    baseEndpoint("eth_chainId", "Returns the chain ID of the network.")
      .out(output[Byte]("chainId"))
      .errorOut(errorNoData)
}
