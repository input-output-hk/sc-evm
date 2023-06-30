package io.iohk.scevm.rpc.controllers

import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.rpc.controllers.NetController.ChainMode
import io.iohk.scevm.rpc.dispatchers.EmptyRequest
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class NetControllerSpec
    extends AsyncWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with NormalPatience {

  private val blockchainConfig: BlockchainConfig = TestCoreConfigs.blockchainConfig
  private val chainId: ChainId                   = blockchainConfig.chainId
  private val networkId: Int                     = blockchainConfig.networkId
  private val stabilityParameter: Int            = 10
  private val slotDuration: FiniteDuration       = 3.seconds

  "NetController" when {
    "version is called" should {
      "return a correct response" in {
        val chainModeConfig: ChainMode = ChainMode.Standalone
        val netController              = new NetController(blockchainConfig, chainModeConfig)

        val response = netController.version().ioValue
        response shouldBe networkId.toString
      }
    }

    "getNetworkInfo is called" should {
      "return a correct response for standalone mode" in {
        val chainModeConfig: ChainMode = ChainMode.Standalone
        val netController              = new NetController(blockchainConfig, chainModeConfig)

        val response = netController.getNetworkInfo(EmptyRequest()).ioValue.value
        response.chainId shouldBe chainId
        response.networkId shouldBe networkId
        response.stabilityParameter shouldBe stabilityParameter
        response.slotDuration shouldBe slotDuration
        response.chainMode shouldBe ChainMode.Standalone
      }

      "return a correct response for sidechain mode" in {
        val chainModeConfig: ChainMode = ChainMode.Sidechain
        val netController              = new NetController(blockchainConfig, chainModeConfig)

        val response = netController.getNetworkInfo(EmptyRequest()).ioValue.value
        response.chainId shouldBe chainId
        response.networkId shouldBe networkId
        response.stabilityParameter shouldBe stabilityParameter
        response.slotDuration shouldBe slotDuration
        response.chainMode shouldBe ChainMode.Sidechain
      }
    }
  }
}
