package io.iohk.scevm.rpc.armadillo.scevm

import cats.effect.IO
import io.iohk.armadillo.{JsonRpcError, JsonRpcServerEndpoint}
import io.iohk.scevm.rpc.controllers.NetController
import io.iohk.scevm.rpc.dispatchers.EmptyRequest

object EvmSidechainNetworkEndpoints {

  def getEndpoints(netController: NetController): List[JsonRpcServerEndpoint[IO]] = List(
    evmsidechain_getNetworkInfo(netController)
  )

  private def evmsidechain_getNetworkInfo(netController: NetController): JsonRpcServerEndpoint[IO] =
    EmvSidechainNetworkApi.evmsidechain_getNetworkInfo.serverLogic[IO] { _ =>
      netController.getNetworkInfo(EmptyRequest()).map {
        case Left(value)  => Left(JsonRpcError.noData(value.code, value.message))
        case Right(value) => Right(Some(value))
      }
    }
}
