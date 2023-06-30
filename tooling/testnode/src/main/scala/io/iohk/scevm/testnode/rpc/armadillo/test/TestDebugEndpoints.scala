package io.iohk.scevm.testnode.rpc.armadillo.test

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.testnode.TestJRpcController
import io.iohk.scevm.testnode.TestJRpcController.{AccountsInRangeParams, StorageRangeParams}

object TestDebugEndpoints extends Endpoints {

  def endpoints(testJRpcController: TestJRpcController[IO]): List[JsonRpcServerEndpoint[IO]] = List(
    debug_accountRange(testJRpcController),
    debug_storageRangeAt(testJRpcController)
  )

  private def debug_accountRange(testJRpcController: TestJRpcController[IO]): JsonRpcServerEndpoint[IO] =
    TestDebugApi.debug_accountRange.serverLogic[IO] {
      case (blockHashOrNumber, transactionIndex, addressHash, maxResults) =>
        testJRpcController
          .getAccountsInRange(
            AccountsInRangeParams(blockHashOrNumber, transactionIndex, addressHash, maxResults)
          )
          .noDataError
          .value
    }

  private def debug_storageRangeAt(testJRpcController: TestJRpcController[IO]): JsonRpcServerEndpoint[IO] =
    TestDebugApi.debug_storageRangeAt.serverLogic[IO] {
      case (blockHashOrNumber, transactionIndex, address, addressHash, maxResults) =>
        testJRpcController
          .storageRangeAt(StorageRangeParams(blockHashOrNumber, transactionIndex, address, addressHash, maxResults))
          .noDataError
          .value
    }
}
