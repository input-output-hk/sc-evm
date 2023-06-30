package io.iohk.scevm.rpc.armadillo.eth

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.EthVMController
import io.iohk.scevm.rpc.controllers.EthVMController.{CallRequest, EstimateGasRequest}

object EthVMEndpoints extends Endpoints {

  def getEthVMEndpoints(ethVMController: EthVMController): List[JsonRpcServerEndpoint[IO]] = List(
    eth_call(ethVMController),
    eth_estimateGas(ethVMController)
  )

  private def eth_call(ethVMController: EthVMController): JsonRpcServerEndpoint[IO] =
    EthVMApi.eth_call.serverLogic[IO] { case (transaction, blockParam) =>
      ethVMController.call(CallRequest(transaction, blockParam)).noDataError.value
    }

  private def eth_estimateGas(ethVMController: EthVMController): JsonRpcServerEndpoint[IO] =
    EthVMApi.eth_estimateGas.serverLogic[IO] { case (transaction, blockParam) =>
      ethVMController.estimateGas(EstimateGasRequest(transaction, blockParam)).noDataError.value
    }

}
