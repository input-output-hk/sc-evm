package io.iohk.scevm.rpc.armadillo.eth

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData, errorWithData}
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Gas, TransactionHash}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.EthTransactionController.GetLogsRequest
import io.iohk.scevm.rpc.controllers.{TransactionLogWithRemoved, TransactionReceiptResponse}
import io.iohk.scevm.rpc.domain.{RpcFullTransactionResponse, TransactionRequest}
import org.json4s.JsonAST.JValue

object EthTransactionApi extends CommonApi {
  private val transactionHashParameter = input[TransactionHash]("transactionHash")

  // scalastyle:off line.size.limit
  val eth_gasPrice: JsonRpcEndpoint[Unit, JsonRpcError[Unit], Gas] =
    baseEndpoint("eth_gasPrice", "Returns the price of gas (in wei).")
      .out(output[Gas]("gas"))
      .errorOut(errorNoData)

  val eth_getLogs: JsonRpcEndpoint[GetLogsRequest, JsonRpcError[Unit], Seq[TransactionLogWithRemoved]] =
    baseEndpoint(
      "eth_getLogs",
      "Returns the list of logs of blocks from a given range matching the given topics. More information about topics here: https://ethereum.org/en/developers/docs/apis/json-rpc/#eth_newfilter"
    )
      .in(input[GetLogsRequest]("request"))
      .out(output[Seq[TransactionLogWithRemoved]]("transactionLogs"))
      .errorOut(errorNoData)

  val eth_getTransactionByHash
      : JsonRpcEndpoint[TransactionHash, JsonRpcError[Unit], Option[RpcFullTransactionResponse]] =
    baseEndpoint(
      "eth_getTransactionByHash",
      "Returns information about the transaction with the given hash."
    )
      .in(transactionHashParameter)
      .out(output[Option[RpcFullTransactionResponse]]("RpcFullTransactionResponse"))
      .errorOut(errorNoData)

  val eth_getTransactionReceipt
      : JsonRpcEndpoint[TransactionHash, JsonRpcError[Unit], Option[TransactionReceiptResponse]] =
    baseEndpoint(
      "eth_getTransactionReceipt",
      "Returns information about the receipt of the transaction with the given hash."
    )
      .in(transactionHashParameter)
      .out(output[Option[TransactionReceiptResponse]]("TransactionReceiptResponse"))
      .errorOut(errorNoData)

  val eth_sendRawTransaction: JsonRpcEndpoint[ByteString, JsonRpcError[Unit], TransactionHash] =
    baseEndpoint("eth_sendRawTransaction", "Sends a signed transaction using the given array of bytes.")
      .in(input[ByteString]("data"))
      .out(output[TransactionHash]("transactionHash"))
      .errorOut(errorNoData)

  val eth_sendTransaction: JsonRpcEndpoint[TransactionRequest, JsonRpcError[JValue], TransactionHash] =
    baseEndpoint(
      "eth_sendTransaction",
      "Sends a transaction using the provided object, which contains fields like the sender and the receiver of the transaction (which can be the address of a smart contract), and the number of tokens to transfer."
    )
      .in(input[TransactionRequest]("TransactionRequest"))
      .out(output[TransactionHash]("transactionHash"))
      .errorOut(errorWithData[JValue])
  // scalastyle:on line.size.limit
}
