package io.iohk.scevm.rpc.armadillo.sanity

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.SanityController

object SanityEndpoints extends Endpoints {

  def endpoints(sanityController: SanityController[IO]): List[JsonRpcServerEndpoint[IO]] = List(
    sanity_checkChainConsistency(sanityController)
  )

  private def sanity_checkChainConsistency(sanityController: SanityController[IO]): JsonRpcServerEndpoint[IO] =
    SanityApi.sanity_checkChainConsistency.serverLogic[IO] { checkChainConsistencyRequest =>
      sanityController
        .checkChainConsistency(checkChainConsistencyRequest)
        .noDataError
        .value

    }
}
