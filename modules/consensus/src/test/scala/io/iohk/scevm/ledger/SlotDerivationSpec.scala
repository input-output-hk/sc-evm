package io.iohk.scevm.ledger

import io.iohk.scevm.domain.Slot
import io.iohk.scevm.testing.Generators
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

import SlotDerivation.{InvalidGenesisTime, InvalidSlotSize}

class SlotDerivationSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "getSlot" when {
    "on valid parameters" should {
      "correspond to the number of times the slot duration has passed" in {
        val slotSize = 100.seconds
        forAll(Generators.unixTsGen(0L, Long.MaxValue), Gen.choose(0, 10000)) { (genesisTime, times) =>
          val elapsed = slotSize * times
          assert(
            SlotDerivation.getSlot(genesisTime, genesisTime.add(elapsed), slotSize).contains(Slot(BigInt(times)))
          )
        }
      }

      "be consistent with the previous and next slot numbers" in {
        val max = Long.MaxValue - Int.MaxValue
        forAll(Generators.unixTsGen(0L, max), Gen.choose(0L, Int.MaxValue), Gen.choose(1, Int.MaxValue)) {
          (genesisTime, elapsedTime, slotSize) =>
            val slotSizeSeconds = slotSize.seconds
            val givenTime       = genesisTime.add(elapsedTime.seconds)
            val slotNumber      = SlotDerivation.getSlot(genesisTime, givenTime, slotSizeSeconds)

            assert(slotNumber.exists(_.number >= 0))

            val next = SlotDerivation.getSlot(genesisTime, givenTime.add(slotSizeSeconds), slotSizeSeconds)
            val prev = SlotDerivation.getSlot(genesisTime, givenTime.sub(slotSizeSeconds), slotSizeSeconds)

            assert(slotNumber.map(s => Slot(s.number + 1)) == next)

            if (elapsedTime >= slotSize) assert(slotNumber.map(s => Slot(s.number - 1)) == prev)
        }
      }
    }

    "on invalid parameters" should {
      val positiveLong = Generators.unixTsGen(0L, Long.MaxValue)

      "not accept slot duration smaller than 1" in {
        forAll(positiveLong, Gen.choose(Int.MinValue, 0)) { (genesisTime, slotSize) =>
          SlotDerivation.live(genesisTime, slotSize.seconds) shouldBe Left(InvalidSlotSize)
        }
      }

      val negativeLong = Gen.choose(Long.MinValue, 0)

      "not accept genesis time with negative value" in {
        forAll(negativeLong, Gen.choose(1, Int.MaxValue)) { (genesisTime, slotSize) =>
          SlotDerivation.live(genesisTime.millisToTs, slotSize.seconds) shouldBe Left(InvalidGenesisTime)
        }
      }

      "not accept given time with negative value" in {
        val slotSize = 100.seconds
        forAll(positiveLong, negativeLong) { (genesisTime, elapsedTime) =>
          val givenTime = genesisTime.add(elapsedTime.nanos)
          SlotDerivation.getSlot(genesisTime, givenTime, slotSize) shouldBe None
        }
      }

      "not accept given time before genesis" in {
        val slotSize = 100.seconds
        val limit    = Int.MaxValue

        // genesis time is higher than the given time
        forAll(Generators.unixTsGen(limit, Long.MaxValue), Gen.choose(0L, limit)) { (genesisTime, givenTime) =>
          SlotDerivation.getSlot(genesisTime, givenTime.millisToTs, slotSize) shouldBe None
        }
      }
    }
  }

  "secondsBeforeNextSlot" when {
    "on valid parameters" should {
      "be consistent with .getSlot" in {
        forAll(Generators.unixTsGen(0L, Long.MaxValue), Gen.choose(1, 1000)) { (genesisTime, slotDuration) =>
          forAll(Generators.unixTsGen(genesisTime.millis, Long.MaxValue)) { currentTime =>
            val slotDerivation = SlotDerivation.live(genesisTime, slotDuration.seconds).toOption.get

            for {
              remainingSeconds   <- slotDerivation.timeToNextSlot(currentTime)
              slot               <- slotDerivation.getSlot(currentTime)
              nextSlot            = Slot(slot.number + 1)
              slotAfterRemaining <- slotDerivation.getSlot(currentTime.add(remainingSeconds))
            } yield nextSlot shouldBe slotAfterRemaining
          }
        }
      }
    }
  }
}
