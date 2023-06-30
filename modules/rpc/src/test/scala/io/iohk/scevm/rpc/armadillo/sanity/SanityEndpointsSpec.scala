package io.iohk.scevm.rpc.armadillo.sanity

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.SanityController.{CheckChainConsistencyRequest, CheckChainConsistencyResponse}
import org.json4s.JsonAST._
import org.json4s.{JArray, JString}

class SanityEndpointsSpec extends ArmadilloScenarios {

  "sanity_checkChainConsistency" shouldPass new EndpointScenarios(SanityApi.sanity_checkChainConsistency) {

    "handle request successfully with all params as string" in successScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_String,
      expectedInputs = Fixtures.sanity_checkChainConsistency.SanityRequestObject,
      serviceResponse = Fixtures.sanity_checkChainConsistency.ExpectedResponse,
      expectedResponse = Fixtures.sanity_checkChainConsistency.ExpectedJsonResponse
    )

    "handle request successfully with all params as integer" in successScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_Int,
      expectedInputs = Fixtures.sanity_checkChainConsistency.SanityRequestObject,
      serviceResponse = Fixtures.sanity_checkChainConsistency.ExpectedResponse,
      expectedResponse = Fixtures.sanity_checkChainConsistency.ExpectedJsonResponse
    )

    "handle request successfully with params as integer and string" in successScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_IntString,
      expectedInputs = Fixtures.sanity_checkChainConsistency.SanityRequestObject,
      serviceResponse = Fixtures.sanity_checkChainConsistency.ExpectedResponse,
      expectedResponse = Fixtures.sanity_checkChainConsistency.ExpectedJsonResponse
    )

    "handle request successfully with single param" in successScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_Single,
      expectedInputs = Fixtures.sanity_checkChainConsistency.SanityRequestObjectSingleParam,
      serviceResponse = Fixtures.sanity_checkChainConsistency.ExpectedResponse,
      expectedResponse = Fixtures.sanity_checkChainConsistency.ExpectedJsonResponse
    )

    "fail when no params is provided" in errorScenario(
      params = JNothing,
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "fail invalid params" in validationFailScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_Invalid,
      expectedResponse = InvalidParamsJson
    )

    "fail because of validation ('to' is not bigger than 'from')" in validationFailScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_ValidationFail,
      expectedResponse = InvalidParamsJson
    )

    "fail because of validation (BlockNumber smaller than zero)" in validationFailScenario(
      params = Fixtures.sanity_checkChainConsistency.SanityRequest_ValidationFailBlockNumber,
      expectedResponse = InvalidParamsJson
    )

  }

  object Fixtures {

    object sanity_checkChainConsistency {

      val ExpectedResponse: CheckChainConsistencyResponse =
        CheckChainConsistencyResponse(BlockNumber(0), BlockNumber(42), List.empty)

      val ExpectedJsonResponse: Json =
        json"""{
                "from"    : "0x0",
                "to"      : "0x2a",
                "errors"  : []
      }"""

      val SanityRequestObject: CheckChainConsistencyRequest =
        CheckChainConsistencyRequest(Some(BlockNumber(0)), Some(BlockNumber(42)))

      val SanityRequestObjectSingleParam: CheckChainConsistencyRequest =
        CheckChainConsistencyRequest(None, Some(BlockNumber(42)))

      val SanityRequest_String: JArray =
        JArray(List(JObject("from" -> JString("0x0"), "to" -> JString("0x2a"))))

      val SanityRequest_Int: JArray =
        JArray(List(JObject("from" -> JInt(0), "to" -> JInt(42))))

      val SanityRequest_IntString: JArray =
        JArray(List(JObject("from" -> JInt(0), "to" -> JString("0x2a"))))

      val SanityRequest_Single: JArray =
        JArray(List(JObject("to" -> JString("0x2a"))))

      val SanityRequest_Invalid: JArray =
        JArray(List(JObject("from" -> JString("not_valid"), "to" -> JInt(42))))

      val SanityRequest_ValidationFail: JArray =
        JArray(List(JObject("from" -> JInt(42), "to" -> JInt(0))))

      val SanityRequest_ValidationFailBlockNumber: JArray =
        JArray(List(JObject("from" -> JString("-10"), "to" -> JInt(42))))
    }

  }
}
