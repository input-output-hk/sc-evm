package io.iohk.scevm.rpc.armadillo.inspect

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{BlockHash, BlockOffset}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.InspectController.GetChainResponse
import org.json4s.JsonAST.{JArray, JInt, JString}

class InspectEndpointsSpec extends ArmadilloScenarios {
  "inspect_getChain" shouldPass new EndpointScenarios(InspectApi.inspect_getChain) {

    val blockHash                              = "e8118a6a0f2ea8447b2418b0301fa53fa97f95a042fc92edbd7eda9f809d9040"
    private val responseStub: GetChainResponse = GetChainResponse(Nil, Nil, Nil, BlockHash(Hex.decodeUnsafe(blockHash)))

    private val responseAsJson: Json = json"""{
        "nodes": [],
        "links": [],
        "best": [],
        "stable": ${"0x" + blockHash}
      }"""

    "should handle inspect_getChain request when offset is not defined" in successScenario(
      params = JArray(Nil),
      expectedInputs = None,
      serviceResponse = responseStub,
      expectedResponse = responseAsJson
    )

    "should handle inspect_getChain request when offset is defined" in successScenario(
      params = JArray(List(JString("0x4"))),
      expectedInputs = Some(BlockOffset(4)),
      serviceResponse = responseStub,
      expectedResponse = responseAsJson
    )

    "should handle inspect_getChain request with an invalid param" in validationFailScenario(
      params = JArray(List(JInt(-1))),
      expectedResponse = json"""{ "code": -32602, "message": "Invalid params" }"""
    )
  }
}
