package io.iohk.scevm.rpc.armadillo.faucet

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.domain.{Address, TransactionHash}
import io.iohk.scevm.rpc.armadillo.CommonApi

object FaucetApi extends CommonApi {

  val faucet_sendFunds: JsonRpcEndpoint[Address, JsonRpcError[Unit], TransactionHash] =
    baseEndpoint(
      "faucet_sendFunds",
      "Transfers some funds from the faucet address to the given address and returns the hash of the corresponding transaction."
    )
      .in(input[Address]("address"))
      .out(output[TransactionHash]("transactionHash"))
      .errorOut(errorNoData)
}
