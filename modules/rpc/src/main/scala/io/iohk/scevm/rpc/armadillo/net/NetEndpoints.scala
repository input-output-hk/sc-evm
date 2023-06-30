package io.iohk.scevm.rpc.armadillo.net

import cats.effect.IO
import cats.syntax.all._
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.NetController

object NetEndpoints extends Endpoints {

  def endpoints(netController: NetController): List[JsonRpcServerEndpoint[IO]] = List(
    net_listening(netController),
    net_version(netController)
  )

  private def net_listening(netController: NetController): JsonRpcServerEndpoint[IO] =
    NetApi.net_listening.serverLogic[IO] { _ =>
      netController.isListening().map(_.asRight)
    }

  private def net_version(netController: NetController): JsonRpcServerEndpoint[IO] =
    NetApi.net_version.serverLogic[IO] { _ =>
      netController.version().map(_.asRight)
    }
}
