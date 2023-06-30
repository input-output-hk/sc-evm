package io.iohk.scevm.ledger

import io.iohk.scevm.domain._
import io.iohk.scevm.testing.BlockGenerators.blockContextGen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BlockRewardCalculatorSpec extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks {
  test("rewards provided address with fixed reward") {
    val reward          = BigInt(1234)
    val addressProvider = new BeneficiaryRewardAddressProvider
    val calculator      = BlockRewardCalculator(reward, addressProvider)
    forAll(blockContextGen) { blockContext =>
      calculator.calculateReward(blockContext) shouldBe BlockRewardCalculator.BlockReward(
        Token(reward),
        blockContext.coinbase
      )
    }
  }
}
