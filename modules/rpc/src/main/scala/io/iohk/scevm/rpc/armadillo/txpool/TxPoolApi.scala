package io.iohk.scevm.rpc.armadillo.txpool

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.TxPoolController.GetContentResponse

object TxPoolApi extends CommonApi {

  // scalastyle:off line.size.limit
  val txpool_content: JsonRpcEndpoint[Unit, JsonRpcError[Unit], GetContentResponse] =
    baseEndpoint(
      "txpool_content",
      """Returns the list of pending and queued transactions.
        |As a user can send multiple transactions, the transactions are grouped by address, and each of them is also associated to a nonce.""".stripMargin
    )
      .out(output[GetContentResponse]("GetContentResponse"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
