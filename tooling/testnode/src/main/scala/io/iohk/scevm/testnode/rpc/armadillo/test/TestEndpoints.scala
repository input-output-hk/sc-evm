package io.iohk.scevm.testnode.rpc.armadillo.test

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.testnode.TestJRpcController

object TestEndpoints extends Endpoints {

  def endpoints(testJRpcController: TestJRpcController[IO]): List[JsonRpcServerEndpoint[IO]] = List(
    test_mineBlocks(testJRpcController),
    test_modifyTimestamp(testJRpcController),
    test_rewindToBlock(testJRpcController),
    test_setChainParams(testJRpcController)
  )

  private def test_mineBlocks(testJRpcController: TestJRpcController[IO]): JsonRpcServerEndpoint[IO] =
    TestApi.test_mineBlocks.serverLogic[IO] { number =>
      testJRpcController
        .mineBlocks(number)
        .noDataError
        .value
    }

  private def test_modifyTimestamp(testJRpcController: TestJRpcController[IO]): JsonRpcServerEndpoint[IO] =
    TestApi.test_modifyTimestamp.serverLogic[IO] { timestamp =>
      testJRpcController
        .modifyTimestamp(timestamp)
        .noDataError
        .value
    }

  private def test_rewindToBlock(testJRpcController: TestJRpcController[IO]): JsonRpcServerEndpoint[IO] =
    TestApi.test_rewindToBlock.serverLogic[IO] { blockNumber =>
      testJRpcController
        .rewindToBlock(blockNumber)
        .noDataError
        .value
    }

  private def test_setChainParams(testJRpcController: TestJRpcController[IO]): JsonRpcServerEndpoint[IO] =
    TestApi.test_setChainParams.serverLogic[IO] { chainParams =>
      testJRpcController
        .setChainParams(chainParams)
        .noDataError
        .value
    }
}
