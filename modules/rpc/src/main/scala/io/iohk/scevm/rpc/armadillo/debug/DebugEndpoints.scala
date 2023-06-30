package io.iohk.scevm.rpc.armadillo.debug

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.DebugController
import io.iohk.scevm.rpc.controllers.DebugController.TraceTransactionRequest

object DebugEndpoints extends Endpoints {

  def endpoints(debugController: DebugController[IO]): List[JsonRpcServerEndpoint[IO]] = List(
    debug_traceTransaction(debugController)
  )

  private def debug_traceTransaction(debugController: DebugController[IO]): JsonRpcServerEndpoint[IO] =
    DebugApi.debug_traceTransaction.serverLogic[IO] { case (transactionHash, traceParameters) =>
      debugController
        .traceTransaction(TraceTransactionRequest(transactionHash, traceParameters))
        .noDataError
        .value
    }
}
