package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.{Address, TransactionHash}
import io.iohk.scevm.rpc.TestRpcConfig
import io.iohk.scevm.rpc.faucet.FaucetController
import io.iohk.scevm.rpc.router.NonceServiceStub
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class FaucetControllerSpec
    extends AsyncWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with NormalPatience {

  private val blockchainConfig: BlockchainConfig = TestCoreConfigs.blockchainConfig
  private val nonceService                       = new NonceServiceStub[IO]()
  private val faucetController = new FaucetController(
    faucetConfig = TestRpcConfig.faucetConfig,
    nonceService = nonceService,
    blockchainConfig = blockchainConfig,
    transactionsImportService = NewTransactionListenerStub()
  )

  "FaucetController" when {
    "sendTransaction is called" should {
      "return a correct response" in {
        val response = faucetController.sendTransaction(Address("0x42")).ioValue
        response shouldBe Right(
          TransactionHash(Hex.decodeUnsafe("84ff451b687ba92fd0a1425193fe53ca9e56b9b213a24a495135f915eab48255"))
        )
      }
    }
  }
}
