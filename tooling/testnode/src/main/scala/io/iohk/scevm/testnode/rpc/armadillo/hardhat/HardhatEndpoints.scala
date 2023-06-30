package io.iohk.scevm.testnode.rpc.armadillo.hardhat

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.domain.Token
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.testnode.HardhatApiController

object HardhatEndpoints extends Endpoints {

  def endpoints(hardhatApiController: HardhatApiController[IO]): List[JsonRpcServerEndpoint[IO]] = List(
    hardhat_setStorageAt(hardhatApiController)
  )

  private def hardhat_setStorageAt(hardhatApiController: HardhatApiController[IO]): JsonRpcServerEndpoint[IO] =
    HardhatApi.hardhat_setStorageAt.serverLogic[IO] { case (address, memorySlot, value) =>
      hardhatApiController
        .setStorage(address, memorySlot, Token(value))
        .noDataError
        .value
    }
}
