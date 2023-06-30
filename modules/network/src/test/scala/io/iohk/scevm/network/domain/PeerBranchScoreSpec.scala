package io.iohk.scevm.network.domain

import io.iohk.scevm.domain.{BlockNumber, Slot}
import io.iohk.scevm.testing.fixtures.ValidBlock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class PeerBranchScoreSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  "The score" should "be the density when a stable-ancestor is available" in {
    val stable   = ValidBlock.header.copy(number = BlockNumber(10), slotNumber = Slot(20))
    val ancestor = ValidBlock.header.copy(number = BlockNumber(20), slotNumber = Slot(10))

    PeerBranchScore.from(stable, Some(ancestor)) shouldBe StableHeaderScore(
      stable,
      ChainDensity.density(stable, ancestor)
    )
  }

  it should "not set when the distance is too short" in {
    val stable = ValidBlock.header.copy(number = BlockNumber(10), slotNumber = Slot(20))

    PeerBranchScore.from(stable, None) shouldBe ChainTooShort(stable)
  }

}
