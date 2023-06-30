package io.iohk.scevm.rpc.armadillo.eth

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.EthBlocksController
import io.iohk.scevm.rpc.controllers.EthBlocksController.{GetBlockByHashRequest, GetBlockByNumberRequest}
import io.iohk.scevm.rpc.dispatchers.EmptyRequest

object EthBlocksEndpoints extends Endpoints {

  def getEthEndpoints(ethBlocksController: EthBlocksController): List[JsonRpcServerEndpoint[IO]] = List(
    eth_blockNumber(ethBlocksController),
    eth_getBlockByHash(ethBlocksController),
    eth_getBlockByNumber(ethBlocksController),
    eth_getBlockTransactionCountByHash(ethBlocksController),
    eth_getBlockTransactionCountByNumber(ethBlocksController)
  )

  private def eth_blockNumber(ethBlocksController: EthBlocksController): JsonRpcServerEndpoint[IO] =
    EthBlocksApi.eth_blockNumber.serverLogic[IO] { _ =>
      ethBlocksController
        .bestBlockNumber(EmptyRequest())
        .noDataError
        .value
    }

  private def eth_getBlockByHash(ethBlocksController: EthBlocksController): JsonRpcServerEndpoint[IO] =
    EthBlocksApi.eth_getBlockByHash.serverLogic[IO] { case (blockHash, fullTxs) =>
      ethBlocksController
        .getBlockByHash(GetBlockByHashRequest(blockHash, fullTxs))
        .noDataErrorMapValue(_.blockResponse)
    }

  private def eth_getBlockByNumber(ethBlocksController: EthBlocksController): JsonRpcServerEndpoint[IO] =
    EthBlocksApi.eth_getBlockByNumber.serverLogic[IO] { case (blockParam, fullTxs) =>
      ethBlocksController
        .getBlockByNumber(GetBlockByNumberRequest(blockParam, fullTxs))
        .noDataErrorMapValue(_.blockResponse)
    }

  private def eth_getBlockTransactionCountByHash(ethBlocksController: EthBlocksController): JsonRpcServerEndpoint[IO] =
    EthBlocksApi.eth_getBlockTransactionCountByHash.serverLogic[IO] { blockHash =>
      ethBlocksController
        .getBlockTransactionCountByHash(blockHash)
        .noDataError
        .value
    }

  private def eth_getBlockTransactionCountByNumber(
      ethBlocksController: EthBlocksController
  ): JsonRpcServerEndpoint[IO] =
    EthBlocksApi.eth_getBlockTransactionCountByNumber.serverLogic[IO] { blockParam =>
      ethBlocksController
        .getBlockTransactionCountByNumber(blockParam)
        .noDataError
        .value
    }
}
