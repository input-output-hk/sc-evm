package io.iohk.scevm.rpc.armadillo.faucet

import io.circe.literal.JsonStringContext
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, TransactionHash}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import org.json4s.JsonAST.{JArray, JString}

class FaucetEndpointsSpec extends ArmadilloScenarios {

  private val addressWithoutPrefix = "0101010101010101010101010101010101010101"
  private val addressWithPrefix    = s"0x$addressWithoutPrefix"

  "faucet_sendFunds" shouldPass new EndpointScenarios(FaucetApi.faucet_sendFunds) {
    "handle valid request (address without prefix)" in successScenario(
      params = JArray(List(JString(addressWithoutPrefix))),
      expectedInputs = Address(Hex.decodeUnsafe(addressWithoutPrefix)),
      serviceResponse = TransactionHash(Hex.decodeUnsafe("42" * 32)),
      expectedResponse = json""""0x4242424242424242424242424242424242424242424242424242424242424242""""
    )

    "handle valid request (address with prefix)" in successScenario(
      params = JArray(List(JString(addressWithPrefix))),
      expectedInputs = Address(Hex.decodeUnsafe(addressWithoutPrefix)),
      serviceResponse = TransactionHash(Hex.decodeUnsafe("42" * 32)),
      expectedResponse = json""""0x4242424242424242424242424242424242424242424242424242424242424242""""
    )

    "handle invalid request" in errorScenario(
      params = JArray(List(JString("0x123"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

}
