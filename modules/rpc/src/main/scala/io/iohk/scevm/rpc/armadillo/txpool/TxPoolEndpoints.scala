package io.iohk.scevm.rpc.armadillo.txpool

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint.Full
import io.iohk.armadillo.{JsonRpcError, JsonRpcServerEndpoint}
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.TxPoolController
import io.iohk.scevm.rpc.controllers.TxPoolController.GetContentResponse

object TxPoolEndpoints extends Endpoints {

  def getTxPoolEndpoints(txPoolController: TxPoolController[IO]): List[JsonRpcServerEndpoint[IO]] = List(
    txpool_content(txPoolController)
  )

  private def txpool_content(
      txPoolController: TxPoolController[IO]
  ): Full[Unit, JsonRpcError[Unit], GetContentResponse, IO] =
    TxPoolApi.txpool_content.serverLogic { _ =>
      txPoolController.getContent().noDataError.value
    }

}
