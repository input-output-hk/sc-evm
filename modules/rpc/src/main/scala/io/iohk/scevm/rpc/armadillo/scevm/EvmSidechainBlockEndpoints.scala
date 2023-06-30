package io.iohk.scevm.rpc.armadillo.scevm

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.EthBlocksController
import io.iohk.scevm.rpc.controllers.EthBlocksController.GetBlockByNumberRequest

object EvmSidechainBlockEndpoints extends Endpoints {

  def getEvmSidechainEndpoints(ethBlocksController: EthBlocksController): List[JsonRpcServerEndpoint[IO]] = List(
    evmsidechain_getBlockByNumber(ethBlocksController)
  )

  private def evmsidechain_getBlockByNumber(ethBlocksController: EthBlocksController): JsonRpcServerEndpoint[IO] =
    EvmSidechainBlockApi.evmsidechain_getBlockByNumber.serverLogic[IO] { case (blockParam, fullTxs) =>
      ethBlocksController
        .getBlockByNumber(GetBlockByNumberRequest(blockParam, fullTxs))
        .noDataErrorMapValue(_.blockResponse)
    }
}
