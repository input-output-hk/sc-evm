package io.iohk.scevm.rpc.armadillo.scevm

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.NetController.GetNetworkInfoResponse

object EmvSidechainNetworkApi extends CommonApi {

  // scalastyle:off line.size.limit
  val evmsidechain_getNetworkInfo: JsonRpcEndpoint[Unit, JsonRpcError[Unit], Option[GetNetworkInfoResponse]] =
    baseEndpoint(
      "evmsidechain_getNetworkInfo",
      "Returns information related to the network like the chain and network ID, the stability parameter and the slot duration, as well as the current running mode"
    )
      .out(output[Option[GetNetworkInfoResponse]]("GetNetworkInfoResponse"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
