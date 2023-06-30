package io.iohk.scevm.rpc.armadillo.net

import io.circe.literal.JsonStringContext
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.NetController.NetVersion
import org.json4s.JsonAST.JArray

class NetEndpointsSpec extends ArmadilloScenarios {

  "net_listening" shouldPass new EndpointScenarios(NetApi.net_listening) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = true,
      expectedResponse = json"true"
    )
  }

  "net_version" shouldPass new EndpointScenarios(NetApi.net_version) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = NetVersion("42"),
      expectedResponse = json""""42""""
    )
  }

}
