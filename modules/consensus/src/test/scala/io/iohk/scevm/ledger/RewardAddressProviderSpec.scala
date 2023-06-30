package io.iohk.scevm.ledger

import io.iohk.scevm.testing.BlockGenerators.blockContextGen
import io.iohk.scevm.testing.Generators.addressGen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class RewardAddressProviderSpec extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks {
  test(s"${classOf[FixedRewardAddressProvider].getSimpleName} always returns fixed address") {
    forAll(blockContextGen, addressGen) { case (blockContext, address) =>
      val rewardAddressProvider = new FixedRewardAddressProvider(address)
      rewardAddressProvider.getRewardAddress(blockContext) shouldBe address
    }
  }

  test(s"${classOf[BeneficiaryRewardAddressProvider].getSimpleName} returns address of beneficiary") {
    val rewardAddressProvider = new BeneficiaryRewardAddressProvider
    forAll(blockContextGen) { blockContext =>
      rewardAddressProvider.getRewardAddress(blockContext) shouldBe blockContext.coinbase
    }
  }
}
