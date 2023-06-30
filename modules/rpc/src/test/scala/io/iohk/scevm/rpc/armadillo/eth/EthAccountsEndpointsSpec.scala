package io.iohk.scevm.rpc.armadillo.eth

import io.circe.literal.JsonStringContext
import io.iohk.scevm.domain.Address
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import org.json4s.JsonAST.JArray

class EthAccountsEndpointsSpec extends ArmadilloScenarios {

  "eth_accounts" shouldPass new EndpointScenarios(EthAccountsApi.eth_accounts) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = List(Address(1), Address(2)),
      expectedResponse = json"""
                          [
                            "0x0000000000000000000000000000000000000001",
                            "0x0000000000000000000000000000000000000002"
                          ]
                          """
    )
  }

}
