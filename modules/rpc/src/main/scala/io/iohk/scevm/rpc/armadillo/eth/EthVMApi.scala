package io.iohk.scevm.rpc.armadillo.eth

import io.iohk.armadillo.json.json4s.jsonRpcCodec
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.domain.Gas
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.EthBlockParam
import io.iohk.scevm.rpc.controllers.EthVMController.{CallResponse, CallTransaction}

object EthVMApi extends CommonApi {

  private val callTransaction = input[CallTransaction]("transaction")
  private val blockParam      = input[EthBlockParam]("block")

  val eth_call: JsonRpcEndpoint[(CallTransaction, EthBlockParam), JsonRpcError[Unit], CallResponse] =
    baseEndpoint(
      "eth_call",
      "Returns the result of the given transaction and the provided block, but without creating a real transaction."
    )
      .in(callTransaction.and(blockParam))
      .out(output[CallResponse]("CallResponse"))
      .errorOut(errorNoData)

  val eth_estimateGas: JsonRpcEndpoint[(CallTransaction, Option[EthBlockParam]), JsonRpcError[Unit], Gas] =
    baseEndpoint(
      "eth_estimateGas",
      "Returns an estimate of the amount of gas that would be necessary to execute the transaction on the blockchain."
    )
      .in(callTransaction.and(input[Option[EthBlockParam]]("block")))
      .out(output[Gas]("gas"))
      .errorOut(errorNoData)
}
