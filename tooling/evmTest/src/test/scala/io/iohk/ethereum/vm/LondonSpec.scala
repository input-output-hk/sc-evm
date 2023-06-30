package io.iohk.ethereum.vm

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Account, Address}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.fixtures.{LondonBlockNumber, blockchainConfig}
import io.iohk.scevm.exec.vm.{EVM, MockStorage, MockWorldState, ProgramContext}
import io.iohk.scevm.testing.fixtures.ValidBlock
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor4
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class LondonSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  "EVM with London hard fork" should {

    "give the correct refunds" in {
      forAll(gasCostTestCases) { case (code, expectedUsedGas, expectedRefund, originalStorageValue) =>
        val vm = EVM[MockWorldState, MockStorage]()

        val context = buildContext(code, originalStorageValue)
        val result  = vm.run(context).ioValue
        val usedGas = context.startGas - result.gasRemaining

        result.gasRefund shouldBe expectedRefund
        usedGas shouldBe expectedUsedGas
      }
    }

  }

  def buildContext(code: String, originalStorageValue: BigInt): ProgramContext[MockWorldState, MockStorage] = {
    val callAddr     = Address(1001)
    val contractAddr = Address(1002)
    val worldState = MockWorldState().copy(
      accounts = Map(callAddr -> Account(), contractAddr -> Account()),
      codeRepo = Map(contractAddr -> ByteString(Hex.decode(code))),
      storages = Map(contractAddr -> MockStorage(Map(BigInt(0) -> originalStorageValue)))
    )
    ProgramContext(
      callerAddr = callAddr,
      originAddr = callAddr,
      recipientAddr = Some(contractAddr),
      maxPriorityFeePerGas = 1,
      startGas = 1000000,
      intrinsicGas = BigInt(0),
      inputData = ByteString.empty,
      value = 0,
      endowment = 0,
      doTransfer = true,
      blockContext = ValidBlock.blockContext.copy(number = LondonBlockNumber),
      callDepth = 0,
      world = worldState,
      initialAddressesToDelete = Set(),
      evmConfig = EvmConfig.LondonConfigBuilder(blockchainConfig),
      originalWorld = worldState,
      warmAddresses = Set(contractAddr, callAddr),
      // Storage is supposed to be warm
      warmStorage = Set(contractAddr -> 0)
    )
  }

  def gasCostTestCases: TableFor4[String, Int, Int, Int] = Table(
    ("code", "used gas", "refund", "original storage value"),
    ("60006000556000600055", 212, 0, 0),
    ("60006000556001600055", 20112, 0, 0),
    ("60016000556000600055", 20112, 19900, 0),
    ("60016000556002600055", 20112, 0, 0),
    ("60016000556001600055", 20112, 0, 0),
    ("60006000556000600055", 3012, 4800, 1),
    ("60006000556001600055", 3012, 2800, 1),
    ("60006000556002600055", 3012, 0, 1),
    ("60026000556000600055", 3012, 4800, 1),
    ("60026000556003600055", 3012, 0, 1),
    ("60026000556001600055", 3012, 2800, 1),
    ("60026000556002600055", 3012, 0, 1),
    ("60016000556000600055", 3012, 4800, 1),
    ("60016000556002600055", 3012, 0, 1),
    ("60016000556001600055", 212, 0, 1),
    ("600160005560006000556001600055", 40118, 19900, 0),
    ("600060005560016000556000600055", 5918, 7600, 1)
  )

}
