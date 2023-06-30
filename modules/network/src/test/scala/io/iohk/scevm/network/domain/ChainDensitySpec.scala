package io.iohk.scevm.network.domain

import io.iohk.scevm.domain.{BlockNumber, Slot}
import io.iohk.scevm.testing.Generators
import io.iohk.scevm.testing.fixtures.ValidBlock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ChainDensitySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "The density" should "equals 0.0 when there are 0 blocks" in {
    val ancestor = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(2))
    val tip      = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(22))

    val result = ChainDensity.density(tip, ancestor)

    result shouldBe 0.0
  }

  it should "equals 0.0 when the chain is only one block" in {
    val tip = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(22))

    val result = ChainDensity.density(tip, tip)

    result shouldBe 0.0
  }

  it should "equals 0.0 when there are negative number of blocks" in {
    val ancestor = ValidBlock.header.copy(number = BlockNumber(10), slotNumber = Slot(2))
    val tip      = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(22))

    val result = ChainDensity.density(tip, ancestor)

    result shouldBe 0.0
  }

  it should "equals 0.0 when there are 0 slots" in {
    val ancestor = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(2))
    val tip      = ValidBlock.header.copy(number = BlockNumber(10), slotNumber = Slot(2))

    val result = ChainDensity.density(tip, ancestor)

    result shouldBe 0.0
  }

  it should "equals 0.8 when there are 20 slots and 16 blocks" in {
    val ancestor = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(2))
    val tip      = ValidBlock.header.copy(number = BlockNumber(17), slotNumber = Slot(22))

    val result = ChainDensity.density(tip, ancestor)

    // There are 16 blocks (1 to 17) and 20 slots (2 to 22)
    result shouldBe 0.8
  }

  it should "equals 1.0 when all slots are filled" in {
    val ancestor = ValidBlock.header.copy(number = BlockNumber(1), slotNumber = Slot(1))
    val tip      = ValidBlock.header.copy(number = BlockNumber(5), slotNumber = Slot(5))

    val result = ChainDensity.density(tip, ancestor)

    result shouldBe 1.0
  }

  it should "property #blocks/#slots is respected" in {
    forAll(Generators.intGen(0, 100), Generators.intGen(1, 100)) { (numberOfBlocks, numberOfSlots) =>
      val ancestor = ValidBlock.header.copy(number = BlockNumber(0), slotNumber = Slot(0))
      val tip      = ValidBlock.header.copy(number = BlockNumber(numberOfBlocks), slotNumber = Slot(numberOfSlots))

      val result = ChainDensity.density(tip, ancestor)

      result shouldBe numberOfBlocks.toDouble / numberOfSlots
    }
  }

}
