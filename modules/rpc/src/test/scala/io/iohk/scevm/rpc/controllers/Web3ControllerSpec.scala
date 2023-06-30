package io.iohk.scevm.rpc.controllers

import io.iohk.scevm.config.AppConfig
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class Web3ControllerSpec
    extends AsyncWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with NormalPatience {

  private val appConfig: AppConfig = TestCoreConfigs.appConfig

  "Web3Controller" when {
    "clientVersion is called" should {
      "return a correct response" in {
        val web3Controller = new Web3Controller(appConfig)

        val response = web3Controller.clientVersion().ioValue
        response shouldBe appConfig.clientVersion
      }
    }
  }
}
