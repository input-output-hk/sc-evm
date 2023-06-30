package io.iohk.scevm.rpc.armadillo.scevm

import io.circe.literal.JsonStringContext
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.NetController.{ChainMode, GetNetworkInfoResponse}
import org.json4s.JsonAST.JArray

import scala.concurrent.duration.DurationInt

class EvmSidechainNetworkEndpointsSpec extends ArmadilloScenarios {

  "evmsidechain_getNetworkInfo" shouldPass new EndpointScenarios(EmvSidechainNetworkApi.evmsidechain_getNetworkInfo) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = Some(GetNetworkInfoResponse(ChainId(61), 42, 10, 3.seconds, ChainMode.Standalone)),
      expectedResponse = json"""
                {
                  "chainId": "0x3d",
                  "networkId": 42,
                  "stabilityParameter": 10,
                  "slotDuration": "3 seconds",
                  "chainMode": "Standalone"
                }
              """
    )
  }

}
