package io.iohk.scevm.rpc.armadillo.eth

import io.iohk.armadillo.JsonRpcError.NoData
import io.iohk.armadillo.json.json4s.jsonRpcCodec
import io.iohk.armadillo.{JsonRpcEndpoint, errorNoData}
import io.iohk.scevm.domain.{Address, Nonce}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.EthBlockParam
import io.iohk.scevm.rpc.controllers.EthWorldStateController.{SmartContractCode, StorageData}

object EthWorldApi extends CommonApi {
  private val blockParam      = input[EthBlockParam]("block")
  private val addressParam    = input[Address]("address")
  private val addressAndBlock = addressParam.and(blockParam)

  val eth_getCode: JsonRpcEndpoint[(Address, EthBlockParam), NoData, Option[SmartContractCode]] =
    baseEndpoint("eth_getCode", "Returns the code of a smart contract at the given address.")
      .in(addressAndBlock)
      .out(output[Option[SmartContractCode]]("GetCodeResponse"))
      .errorOut(errorNoData)
  val eth_getBalance: JsonRpcEndpoint[(Address, EthBlockParam), NoData, BigInt] =
    baseEndpoint("eth_getBalance", "Returns the number of tokens owned by the account of the given address.")
      .in(addressAndBlock)
      .out(output[BigInt]("balance"))
      .errorOut(errorNoData)
  val eth_getStorageAt: JsonRpcEndpoint[(Address, BigInt, EthBlockParam), NoData, StorageData] =
    baseEndpoint("eth_getStorageAt", "Returns the value in storage for a given position and address.")
      .in(addressParam.and(input[BigInt]("position")).and(blockParam))
      .out(output[StorageData]("StorageData"))
      .errorOut(errorNoData)
  val eth_getTransactionCount: JsonRpcEndpoint[(Address, EthBlockParam), NoData, Nonce] =
    baseEndpoint("eth_getTransactionCount", "Returns the number of transactions that were sent from the given address.")
      .in(addressAndBlock)
      .out(output[Nonce]("nonce"))
      .errorOut(errorNoData)
}
