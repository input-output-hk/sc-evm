package io.iohk.scevm.rpc.armadillo.web3

import io.circe.literal.JsonStringContext
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion
import org.json4s.JsonAST.JArray

class Web3EndpointsSpec extends ArmadilloScenarios {

  "web3_clientVersion" shouldPass new EndpointScenarios(Web3Api.web3_clientVersion) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = ClientVersion("42"),
      expectedResponse = json""""42""""
    )
  }

}
