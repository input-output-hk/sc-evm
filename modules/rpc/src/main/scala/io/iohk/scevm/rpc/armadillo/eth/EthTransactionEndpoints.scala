package io.iohk.scevm.rpc.armadillo.eth

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.{EthTransactionController, PersonalController}

object EthTransactionEndpoints extends Endpoints {

  def endpoints(
      ethTransactionController: EthTransactionController,
      personalController: PersonalController
  ): List[JsonRpcServerEndpoint[IO]] = List(
    eth_gasPrice(ethTransactionController),
    eth_getTransactionByHash(ethTransactionController),
    eth_getTransactionReceipt(ethTransactionController),
    eth_sendRawTransaction(ethTransactionController),
    eth_getLogs(ethTransactionController),
    eth_sendTransaction(personalController)
  )

  private def eth_gasPrice(ethTransactionController: EthTransactionController): JsonRpcServerEndpoint[IO] =
    EthTransactionApi.eth_gasPrice.serverLogic[IO] { _ =>
      ethTransactionController
        .gasPrice()
        .noDataError
        .value
    }

  private def eth_getLogs(ethTransactionController: EthTransactionController): JsonRpcServerEndpoint[IO] =
    EthTransactionApi.eth_getLogs.serverLogic[IO] { getLogsRequest =>
      ethTransactionController
        .getLogs(getLogsRequest)
        .noDataError
        .value
    }

  private def eth_getTransactionByHash(ethTransactionController: EthTransactionController): JsonRpcServerEndpoint[IO] =
    EthTransactionApi.eth_getTransactionByHash.serverLogic[IO] { transactionHash =>
      ethTransactionController
        .getTransactionByHash(transactionHash)
        .noDataError
        .value
    }

  private def eth_getTransactionReceipt(ethTransactionController: EthTransactionController): JsonRpcServerEndpoint[IO] =
    EthTransactionApi.eth_getTransactionReceipt.serverLogic[IO] { transactionHash =>
      ethTransactionController
        .getTransactionReceipt(transactionHash)
        .noDataError
        .value
    }

  private def eth_sendRawTransaction(ethTransactionController: EthTransactionController): JsonRpcServerEndpoint[IO] =
    EthTransactionApi.eth_sendRawTransaction.serverLogic[IO] { data =>
      ethTransactionController
        .sendRawTransaction(data)
        .noDataError
        .value
    }

  def eth_sendTransaction(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    EthTransactionApi.eth_sendTransaction.serverLogic { tx =>
      personalController.sendTransaction(tx).withDataMapValue(_.txHash)
    }
}
