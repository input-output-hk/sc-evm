package io.iohk.scevm.sidechain

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, Token}
import io.iohk.scevm.exec.vm.{EVM, IOEvmCall, StorageType, TransactionSimulator, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.IncomingTransactionId
import io.iohk.scevm.sidechain.EVMTestUtils.EVMFixture
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BridgeContractScalaInterfaceSpec
    extends AnyWordSpec
    with Matchers
    with IOSupport
    with ScalaFutures
    with NormalPatience {

  val bridgeAddress: Address = Address("0x696f686b2e6d616d626100000000000000000000")
  val fixture: EVMFixture    = EVMFixture(bridgeAddress -> EVMTestUtils.loadContractCodeFromFile("BridgeMock.bin-runtime"))

  "BridgeContract.getTransactionsInBatch" should {

    "return the expected value " in {
      val expectedResult = Seq(
        OutgoingTransaction(
          Token(10),
          OutgoingTxRecipient(Hex.decodeUnsafe("61626364")),
          OutgoingTxId(1)
        )
      )
      fixture
        .run(bridgeContract.getTransactionsInBatch(0).run)
        .ioValue shouldBe expectedResult
    }
  }

  "BridgeContract.isIncomingTransactionHandled" should {

    "return true" in {
      fixture
        .run(
          bridgeContract
            .isIncomingTransactionHandled(IncomingTransactionId(ByteString.empty))
            .run
        )
        .ioValue shouldBe Right(true)
    }

    "return false" in {
      fixture
        .run(
          bridgeContract
            .isIncomingTransactionHandled(IncomingTransactionId(ByteString("someid")))
            .run
        )
        .ioValue shouldBe Right(false)
    }
  }

  private def bridgeContract: BridgeContract[IOEvmCall, ECDSA] =
    new BridgeContract(
      chainId = TestCoreConfigs.blockchainConfig.chainId,
      bridgeContractAddress = bridgeAddress,
      transactionSimulator = TransactionSimulator[IO](
        TestCoreConfigs.blockchainConfig,
        EVM[WorldType, StorageType](),
        s => IO.pure(s)
      )
    )
}
