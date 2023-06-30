package io.iohk.scevm.rpc.armadillo.scevm

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.ExtendedBlockParam
import io.iohk.scevm.rpc.domain.RpcBlockResponse

object EvmSidechainBlockApi extends CommonApi {

  // scalastyle:off line.size.limit
  val evmsidechain_getBlockByNumber
      : JsonRpcEndpoint[(ExtendedBlockParam, Boolean), JsonRpcError[Unit], Option[RpcBlockResponse]] =
    baseEndpoint(
      "evmsidechain_getBlockByNumber",
      """Returns information about the block that corresponds to either a specific block number or one of the tags:
        |- `earliest`: corresponds to the first block (genesis)
        |- `latest`: corresponds to the latest unstable block
        |- `pending`: corresponds to the latest unstable block
        |- `stable`: corresponds to the latest stable block
        |If the `fullTxs` is true, all the information about the transactions contained in the block will be displayed. Otherwise, only the hash of the transactions will be displayed.""".stripMargin
    )
      .in(input[ExtendedBlockParam]("blockParam").and(input[Boolean]("fullTxs")))
      .out(output[Option[RpcBlockResponse]]("RpcBlockResponse"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
