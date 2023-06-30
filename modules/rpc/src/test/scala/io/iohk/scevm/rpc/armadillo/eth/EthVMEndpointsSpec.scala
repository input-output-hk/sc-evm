package io.iohk.scevm.rpc.armadillo.eth

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, BlockNumber, Gas, Token}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.BlockParam
import io.iohk.scevm.rpc.controllers.EthVMController.{CallResponse, CallTransaction}
import org.json4s.JsonAST._
import org.scalatest.EitherValues

class EthVMEndpointsSpec extends ArmadilloScenarios with EitherValues {

  "eth_call" shouldPass new EndpointScenarios(EthVMApi.eth_call) {
    "handle valid request (by-number)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("0x1")),
      expectedInputs = (Fixtures.CallTransactionObject, BlockParam.ByNumber(BlockNumber(1))),
      serviceResponse = Fixtures.eth_call.CallResponse,
      expectedResponse = Fixtures.eth_call.ExpectedCallResponseSerialization
    )

    "handle valid request (earliest block)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("earliest")),
      expectedInputs = (Fixtures.CallTransactionObject, BlockParam.Earliest),
      serviceResponse = Fixtures.eth_call.CallResponse,
      expectedResponse = Fixtures.eth_call.ExpectedCallResponseSerialization
    )

    "handle valid request (pending block)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("pending")),
      expectedInputs = (Fixtures.CallTransactionObject, BlockParam.Pending),
      serviceResponse = Fixtures.eth_call.CallResponse,
      expectedResponse = Fixtures.eth_call.ExpectedCallResponseSerialization
    )

    "handle valid request (latest block)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("latest")),
      expectedInputs = (Fixtures.CallTransactionObject, BlockParam.Latest),
      serviceResponse = Fixtures.eth_call.CallResponse,
      expectedResponse = Fixtures.eth_call.ExpectedCallResponseSerialization
    )

    "handle invalid request" in errorScenario(
      params = Fixtures.eth_call.RequestBody(JString("stable")),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "eth_estimateGas" shouldPass new EndpointScenarios(EthVMApi.eth_estimateGas) {
    "handle valid request (by-number)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("0x1")),
      expectedInputs = (Fixtures.CallTransactionObject, Some(BlockParam.ByNumber(BlockNumber(1)))),
      serviceResponse = Fixtures.eth_estimateGas.EstimateGasResponse,
      expectedResponse = Fixtures.eth_estimateGas.ExpectedEstimateGasResponseSerialization
    )

    "handle valid request (earliest block)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("earliest")),
      expectedInputs = (Fixtures.CallTransactionObject, Some(BlockParam.Earliest)),
      serviceResponse = Fixtures.eth_estimateGas.EstimateGasResponse,
      expectedResponse = Fixtures.eth_estimateGas.ExpectedEstimateGasResponseSerialization
    )

    "handle valid request (pending block)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("pending")),
      expectedInputs = (Fixtures.CallTransactionObject, Some(BlockParam.Pending)),
      serviceResponse = Fixtures.eth_estimateGas.EstimateGasResponse,
      expectedResponse = Fixtures.eth_estimateGas.ExpectedEstimateGasResponseSerialization
    )

    "handle valid request (latest block)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JString("latest")),
      expectedInputs = (Fixtures.CallTransactionObject, Some(BlockParam.Latest)),
      serviceResponse = Fixtures.eth_estimateGas.EstimateGasResponse,
      expectedResponse = Fixtures.eth_estimateGas.ExpectedEstimateGasResponseSerialization
    )

    "handle valid request (using default BlockParam)" in successScenario(
      params = Fixtures.eth_call.RequestBody(JNothing),
      expectedInputs = (Fixtures.CallTransactionObject, None),
      serviceResponse = Fixtures.eth_estimateGas.EstimateGasResponse,
      expectedResponse = Fixtures.eth_estimateGas.ExpectedEstimateGasResponseSerialization
    )

    "handle invalid request" in errorScenario(
      params = Fixtures.eth_call.RequestBody(JString("stable")),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  object Fixtures {
    val CallTransactionObject: CallTransaction = CallTransaction(
      to = Some(Address(Hex.decodeUnsafe("0000000000000000000000000000000000000002"))),
      from = Some(Hex.decodeUnsafe("0000000000000000000000000000000000000042")),
      input = Hex.decodeUnsafe(""),
      gas = Some(Gas(30400)),
      gasPrice = Some(Token(10000000000000L)),
      value = Token(273)
    )

    val CallTransactionJson: JObject = JObject(
      "to"       -> JString("0x0000000000000000000000000000000000000002"),
      "from"     -> JString("0x0000000000000000000000000000000000000042"),
      "input"    -> JString(""),
      "gas"      -> JString("0x76c0"),
      "gasPrice" -> JString("0x9184e72a000"),
      "value"    -> JString("0x111")
    )
    object eth_call {

      def RequestBody(blockParam: JValue): JArray = JArray(CallTransactionJson :: blockParam :: Nil)

      val CallResponse = new CallResponse(
        Hex.decodeUnsafe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
      )

      val ExpectedCallResponseSerialization: Json =
        json""""0xe3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""""
    }

    object eth_estimateGas {
      def RequestBody(blockParam: JValue): JArray        = JArray(CallTransactionJson :: blockParam :: Nil)
      val EstimateGasResponse: Gas                       = Gas(BigInt(1000))
      val ExpectedEstimateGasResponseSerialization: Json = json""""0x3e8""""
    }
  }
}
