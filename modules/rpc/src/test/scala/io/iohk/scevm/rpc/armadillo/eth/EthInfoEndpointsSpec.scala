package io.iohk.scevm.rpc.armadillo.eth

import io.circe.literal.JsonStringContext
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import org.json4s.JsonAST.JArray

class EthInfoEndpointsSpec extends ArmadilloScenarios {

  "eth_chainId" shouldPass new EndpointScenarios(EthInfoApi.eth_chainId) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = 61.toByte,
      expectedResponse = json""""0x3d""""
    )
  }

}
