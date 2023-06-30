package io.iohk.scevm.rpc.armadillo.inspect

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.InspectController

object InspectEndpoints extends Endpoints {

  def endpoints(inspectController: InspectController): List[JsonRpcServerEndpoint[IO]] = List(
    inspect_getChain(inspectController)
  )

  private def inspect_getChain(inspectController: InspectController): JsonRpcServerEndpoint[IO] =
    InspectApi.inspect_getChain.serverLogic[IO] { maybeOffset =>
      inspectController
        .getChain(maybeOffset)
        .noDataError
        .value
    }
}
