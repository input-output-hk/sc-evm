package io.iohk.scevm.rpc.armadillo.eth

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.PersonalController

object EthAccountsEndpoints extends Endpoints {

  def endpoints(personalController: PersonalController): List[JsonRpcServerEndpoint[IO]] = List(
    eth_accounts(personalController)
  )

  private def eth_accounts(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    EthAccountsApi.eth_accounts.serverLogic[IO] { _ =>
      personalController
        .listAccounts()
        .noDataError
        .value
    }
}
