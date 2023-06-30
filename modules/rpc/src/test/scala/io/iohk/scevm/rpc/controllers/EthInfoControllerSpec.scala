package io.iohk.scevm.rpc.controllers

import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.rpc.dispatchers.EmptyRequest
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class EthInfoControllerSpec
    extends AsyncWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with NormalPatience {

  private val blockchainConfig: BlockchainConfig = TestCoreConfigs.blockchainConfig

  "EthInfoController" when {
    "chainId is called" should {
      "return a correct response" in {
        val ethInfoController = new EthInfoController(blockchainConfig)

        val response = ethInfoController.chainId(EmptyRequest()).ioValue.value
        response shouldBe blockchainConfig.chainId.value
      }
    }
  }
}
