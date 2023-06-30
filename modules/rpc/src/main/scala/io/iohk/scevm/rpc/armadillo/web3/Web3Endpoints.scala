package io.iohk.scevm.rpc.armadillo.web3

import cats.effect.IO
import cats.syntax.all._
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.Web3Controller

object Web3Endpoints extends Endpoints {

  def endpoints(web3Controller: Web3Controller): List[JsonRpcServerEndpoint[IO]] = List(
    web3_clientVersion(web3Controller)
  )

  private def web3_clientVersion(web3Controller: Web3Controller): JsonRpcServerEndpoint[IO] =
    Web3Api.web3_clientVersion.serverLogic[IO] { _ =>
      web3Controller.clientVersion().map(_.asRight)
    }
}
