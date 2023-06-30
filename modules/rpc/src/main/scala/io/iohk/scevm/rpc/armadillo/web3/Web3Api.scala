package io.iohk.scevm.rpc.armadillo.web3

import io.iohk.armadillo.JsonRpcEndpoint
import io.iohk.armadillo.json.json4s._
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion

object Web3Api extends CommonApi {

  val web3_clientVersion: JsonRpcEndpoint[Unit, Unit, ClientVersion] =
    baseEndpoint("web3_clientVersion", "Returns the client version.")
      .out(output[ClientVersion]("version"))
}
