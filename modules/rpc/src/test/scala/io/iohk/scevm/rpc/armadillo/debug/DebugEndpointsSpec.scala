package io.iohk.scevm.rpc.armadillo.debug

import io.circe.literal.JsonStringContext
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.TransactionHash
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.DebugController.{TraceParameters, TraceTransactionResponse}
import org.json4s.JsonAST.{JArray, JObject, JString}

import scala.concurrent.duration.DurationInt

class DebugEndpointsSpec extends ArmadilloScenarios {

  "debug_traceTransaction" shouldPass new EndpointScenarios(DebugApi.debug_traceTransaction) {

    private val tracer: String =
      """
        |{
        |  "step": function(log, db) {},
        |  "fault": function(log, db) {},
        |  "result": function(ctx, db) { return true; }
        |}
        |""".stripMargin

    private val timeout: String = "10 seconds"

    "handle valid request (all parameters are present)" in successScenario(
      params = JArray(
        List(
          JString("98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb"),
          JObject(
            ("tracer", JString(tracer)),
            ("timeout", JString(timeout))
          )
        )
      ),
      expectedInputs = (
        TransactionHash(Hex.decodeUnsafe("98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb")),
        TraceParameters(tracer, Some(10.seconds))
      ),
      serviceResponse = TraceTransactionResponse("trace response can be anything"),
      expectedResponse = json""""trace response can be anything""""
    )

    "handle valid request (timeout is missing)" in successScenario(
      params = JArray(
        List(
          JString("98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb"),
          JObject(("tracer", JString(tracer)))
        )
      ),
      expectedInputs = (
        TransactionHash(Hex.decodeUnsafe("98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb")),
        TraceParameters(tracer, None)
      ),
      serviceResponse = TraceTransactionResponse("trace response can be anything"),
      expectedResponse = json""""trace response can be anything""""
    )

    "handle invalid request (tracer is missing)" in errorScenario(
      params = JArray(List(JString("98263c8f1f3df5790e3427123be6f75dc72a248f8145e20036572d60dece71eb"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "handle invalid request (transaction hash is missing)" in errorScenario(
      params = JArray(List(JObject(("tracer", JString(tracer))))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

}
