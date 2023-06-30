package io.iohk.scevm.rpc.armadillo.eth

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.EthInfoController
import io.iohk.scevm.rpc.dispatchers.EmptyRequest

object EthInfoEndpoints extends Endpoints {

  def endpoints(ethInfoController: EthInfoController): List[JsonRpcServerEndpoint[IO]] = List(
    eth_chainId(ethInfoController)
  )

  private def eth_chainId(ethInfoController: EthInfoController): JsonRpcServerEndpoint[IO] =
    EthInfoApi.eth_chainId.serverLogic[IO] { _ =>
      ethInfoController
        .chainId(EmptyRequest())
        .noDataError
        .value
    }
}
