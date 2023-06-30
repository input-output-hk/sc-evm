package io.iohk.scevm.rpc.armadillo.eth

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.armadillo.JsonRpcError
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, Nonce}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.EthWorldStateController.{SmartContractCode, StorageData}
import io.iohk.scevm.rpc.controllers.{BlockParam, BlocksFixture}
import org.json4s.JsonAST.{JArray, JString, JValue}

class EthWorldEndpointsSpec extends ArmadilloScenarios with BlocksFixture {

  "eth_getCode" shouldPass new EndpointScenarios(EthWorldApi.eth_getCode) {
    "handle valid request (address is found)" in successScenario(
      params = Fixtures.eth_getCode.Request,
      expectedInputs = (Address(Hex.decodeUnsafe("7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D")), BlockParam.Latest),
      serviceResponse = Fixtures.eth_getCode.Found.ServiceResponse,
      expectedResponse = Fixtures.eth_getCode.Found.ExpectedResponse
    )

    "handle valid request (address is not found)" in successScenario(
      params = Fixtures.eth_getCode.Request,
      expectedInputs = (Address(Hex.decodeUnsafe("7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D")), BlockParam.Latest),
      serviceResponse = Fixtures.eth_getCode.NotFound.ServiceResponse,
      expectedResponse = Fixtures.eth_getCode.NotFound.ExpectedResponse
    )
  }

  "eth_getBalance" shouldPass new EndpointScenarios(EthWorldApi.eth_getBalance) {
    "handle valid request" in successScenario(
      params = Fixtures.eth_getBalance.Request,
      expectedInputs = (Address(Hex.decodeUnsafe("7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D")), BlockParam.Latest),
      serviceResponse = Fixtures.eth_getBalance.ServiceResponse,
      expectedResponse = Fixtures.eth_getBalance.ExpectedResponse
    )
  }

  "eth_getStorageAt" shouldPass new EndpointScenarios(EthWorldApi.eth_getStorageAt) {
    "handle valid request" in successScenario(
      params = Fixtures.eth_getStorageAt.Request,
      expectedInputs = (Address(Hex.decodeUnsafe("7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D")), 1, BlockParam.Latest),
      serviceResponse = Fixtures.eth_getStorageAt.ServiceResponse,
      expectedResponse = Fixtures.eth_getStorageAt.ExpectedResponse
    )
  }

  "eth_getTransactionCount" shouldPass new EndpointScenarios(EthWorldApi.eth_getTransactionCount) {
    "handle valid request" in successScenario(
      params = Fixtures.eth_getTransactionCount.Successful.Request,
      expectedInputs = (Address(Hex.decodeUnsafe("7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D")), BlockParam.Latest),
      serviceResponse = Fixtures.eth_getTransactionCount.Successful.ServiceResponse,
      expectedResponse = Fixtures.eth_getTransactionCount.Successful.ExpectedResponse
    )

    "handle invalid request" in errorScenario(
      params = Fixtures.eth_getTransactionCount.Unsuccessful.Request,
      serverError = Fixtures.eth_getTransactionCount.Unsuccessful.ServiceResponse,
      expectedResponse = Fixtures.eth_getTransactionCount.Unsuccessful.ExpectedResponse
    )
  }

  object Fixtures {

    object eth_getCode {
      val Request: JValue = JArray(
        JString("0x7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D") :: JString("latest") :: Nil
      )

      object Found {
        val ServiceResponse: Option[SmartContractCode] = Option(
          SmartContractCode(ByteString(Hex.decodeUnsafe("FFAA22")))
        )

        val ExpectedResponse: Json = json""""0xffaa22""""
      }

      object NotFound {
        val ServiceResponse: Option[SmartContractCode] = None

        val ExpectedResponse: Json = json"""null"""
      }
    }

    object eth_getBalance {
      val Request: JValue = JArray(
        JString("0x7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D") :: JString("latest") :: Nil
      )

      val ServiceResponse: BigInt = BigInt(123)
      val ExpectedResponse: Json  = json""""0x7b""""
    }

    object eth_getStorageAt {
      val Request: JValue = JArray(
        JString("0x7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D") :: JString("0x1") :: JString("latest") :: Nil
      )

      val ServiceResponse: StorageData =
        StorageData(Hex.decodeUnsafe("8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71"))

      val ExpectedResponse: Json = json""""0x8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71""""
    }

    object eth_getTransactionCount {
      object Successful {
        val Request: JValue = JArray(
          JString("0x7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D") :: JString("latest") :: Nil
        )

        val ServiceResponse: Nonce = Nonce(123)
        val ExpectedResponse: Json = json""""0x7b""""
      }

      object Unsuccessful {
        val Request: JValue = JArray(
          JString("0xabbb6bebfa05aa13e908eaa492bd7a8343760477") :: JString("latest") :: Nil
        )

        val ServiceResponse: JsonRpcError[Unit] = JsonRpcError.noData(100, "State node doesn't exist")
        val ExpectedResponse: Json              = json"""{"code":  100, "message": "State node doesn't exist"}"""
      }

    }

  }
}
