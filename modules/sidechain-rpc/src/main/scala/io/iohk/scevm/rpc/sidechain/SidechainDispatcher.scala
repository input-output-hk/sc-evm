package io.iohk.scevm.rpc.sidechain

import cats.MonadThrow
import io.iohk.scevm.rpc.JsonRpcConfig
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes

object SidechainDispatcher {
  def handleSidechainRequests[F[_]: MonadThrow](
      config: JsonRpcConfig,
      sidechainController: SidechainController[F],
      crossChainTransactionsController: CrossChainTransactionController[F]
  ): RpcRoutes[F] =
    if (config.sidechain) {
      RpcRoutes(
        SidechainEndpoints.getSidechainEndpoints(sidechainController, crossChainTransactionsController),
        List.empty
      )
    } else {
      RpcRoutes.empty[F]
    }
}
