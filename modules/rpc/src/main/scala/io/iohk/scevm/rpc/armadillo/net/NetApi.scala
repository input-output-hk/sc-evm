package io.iohk.scevm.rpc.armadillo.net

import io.iohk.armadillo.JsonRpcEndpoint
import io.iohk.armadillo.json.json4s._
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.NetController.NetVersion

object NetApi extends CommonApi {

  val net_listening: JsonRpcEndpoint[Unit, Unit, Boolean] =
    baseEndpoint("net_listening", "Returns true if listening")
      .out(output[Boolean]("isListening"))

  val net_version: JsonRpcEndpoint[Unit, Unit, NetVersion] =
    baseEndpoint("net_version", "Returns the network ID.")
      .out(output[NetVersion]("networkVersion"))
}
