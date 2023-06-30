package io.iohk.scevm.rpc.armadillo.eth

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.domain.Address
import io.iohk.scevm.rpc.armadillo.CommonApi

object EthAccountsApi extends CommonApi {

  val eth_accounts: JsonRpcEndpoint[Unit, JsonRpcError[Unit], List[Address]] =
    baseEndpoint("eth_accounts", "Returns the list of accounts belonging to the client.")
      .out(output[List[Address]]("addresses"))
      .errorOut(errorNoData)
}
