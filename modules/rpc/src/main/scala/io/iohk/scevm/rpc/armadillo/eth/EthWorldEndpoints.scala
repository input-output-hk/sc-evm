package io.iohk.scevm.rpc.armadillo.eth

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.EthWorldStateController
import io.iohk.scevm.rpc.controllers.EthWorldStateController.{
  GetBalanceRequest,
  GetCodeRequest,
  GetStorageAtRequest,
  GetTransactionCountRequest
}

object EthWorldEndpoints extends Endpoints {
  def getEthWorldEndpoints(controller: EthWorldStateController): Seq[JsonRpcServerEndpoint[IO]] = List(
    eth_getCode(controller),
    eth_getBalance(controller),
    eth_getStorageAt(controller),
    eth_getTransactionCount(controller)
  )

  private def eth_getCode(controller: EthWorldStateController): JsonRpcServerEndpoint[IO] =
    EthWorldApi.eth_getCode.serverLogic { case (address, block) =>
      controller.getCode(GetCodeRequest(address, block)).noDataError.value
    }

  private def eth_getBalance(controller: EthWorldStateController): JsonRpcServerEndpoint[IO] =
    EthWorldApi.eth_getBalance.serverLogic { case (address, block) =>
      controller.getBalance(GetBalanceRequest(address, block)).noDataError.value
    }

  private def eth_getStorageAt(controller: EthWorldStateController): JsonRpcServerEndpoint[IO] =
    EthWorldApi.eth_getStorageAt.serverLogic { case (address, position, block) =>
      controller.getStorageAt(GetStorageAtRequest(address, position, block)).noDataError.value
    }

  private def eth_getTransactionCount(controller: EthWorldStateController): JsonRpcServerEndpoint[IO] =
    EthWorldApi.eth_getTransactionCount.serverLogic { case (address, block) =>
      controller.getTransactionCount(GetTransactionCountRequest(address, block)).noDataError.value
    }
}
