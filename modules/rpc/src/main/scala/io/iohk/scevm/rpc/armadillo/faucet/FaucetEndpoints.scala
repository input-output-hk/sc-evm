package io.iohk.scevm.rpc.armadillo.faucet

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.faucet.FaucetController

object FaucetEndpoints extends Endpoints {

  def endpoints(faucetController: FaucetController): List[JsonRpcServerEndpoint[IO]] = List(
    faucet_sendFunds(faucetController)
  )

  private def faucet_sendFunds(faucetController: FaucetController): JsonRpcServerEndpoint[IO] =
    FaucetApi.faucet_sendFunds.serverLogic[IO] { address =>
      faucetController
        .sendTransaction(address)
        .noDataError
        .value
    }
}
