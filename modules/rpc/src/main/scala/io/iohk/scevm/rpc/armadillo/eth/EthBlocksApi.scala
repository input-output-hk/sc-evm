package io.iohk.scevm.rpc.armadillo.eth

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.EthBlockParam
import io.iohk.scevm.rpc.domain.RpcBlockResponse

object EthBlocksApi extends CommonApi {
  // scalastyle:off line.size.limit
  private val blockHash  = input[BlockHash]("blockHash")
  private val fullTx     = input[Boolean]("fullTxs")
  private val blockParam = input[EthBlockParam]("block")

  val eth_blockNumber: JsonRpcEndpoint[Unit, JsonRpcError[Unit], BlockNumber] =
    baseEndpoint("eth_blockNumber", "Returns the most recent unstable block's number.")
      .out(output[BlockNumber]("blockNumber"))
      .errorOut(errorNoData)

  val eth_getBlockByHash: JsonRpcEndpoint[(BlockHash, Boolean), JsonRpcError[Unit], Option[RpcBlockResponse]] =
    baseEndpoint(
      "eth_getBlockByHash",
      """Returns information about the block with the given hash.
        |If `fullTxs` is true, all the information about the transactions contained in the block will be displayed. Otherwise, only the hash of the transactions will be displayed.""".stripMargin
    )
      .in(blockHash.and(fullTx))
      .out(output[Option[RpcBlockResponse]]("RpcBlockResponse"))
      .errorOut(errorNoData)

  val eth_getBlockByNumber: JsonRpcEndpoint[(EthBlockParam, Boolean), JsonRpcError[Unit], Option[RpcBlockResponse]] =
    baseEndpoint(
      "eth_getBlockByNumber",
      """Returns information about the block that corresponds to either a specific block number or one of the tags:
        |- `earliest`: corresponds to the first block (genesis)
        |- `latest`: corresponds to the latest unstable block
        |- `pending`: corresponds to the latest unstable block
        |If `fullTxs` is true, all the information about the transactions contained in the block will be displayed. Otherwise, only the hash of the transactions will be displayed.""".stripMargin
    )
      .in(blockParam.and(fullTx))
      .out(output[Option[RpcBlockResponse]]("RpcBlockResponse"))
      .errorOut(errorNoData)

  val eth_getBlockTransactionCountByHash: JsonRpcEndpoint[BlockHash, JsonRpcError[Unit], Option[Int]] =
    baseEndpoint(
      "eth_getBlockTransactionCountByHash",
      "Returns the number of transactions contained in the block with the given hash."
    )
      .in(blockHash)
      .out(output[Option[Int]]("TransactionCount"))
      .errorOut(errorNoData)

  val eth_getBlockTransactionCountByNumber: JsonRpcEndpoint[EthBlockParam, JsonRpcError[Unit], BigInt] =
    baseEndpoint(
      "eth_getBlockTransactionCountByNumber",
      "Returns the number of transactions contained in the block with the given number."
    )
      .in(blockParam)
      .out(output[BigInt]("TransactionCount"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
