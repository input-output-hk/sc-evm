package io.iohk.scevm.consensus.validators

import io.iohk.scevm.consensus.validators.ChainingValidator.{
  GasLimitError,
  MinGasLimit,
  NumberError,
  ParentHashError,
  SlotNumberError,
  TimestampError
}
import io.iohk.scevm.domain.{BlockNumber, Slot}
import io.iohk.scevm.testing.BlockGenerators.{obftBlockHeaderGen, obftHeaderChildGen}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ChainingValidatorImplSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with EitherValues {

  "ChainingValidator" when {
    lazy val validator = new ChainingValidatorImpl
    "validate parent/child headers" should {
      "pass on proper headers" in forAll(obftBlockHeaderGen) { parent =>
        forAll(obftHeaderChildGen(parent)) { child =>
          validator.validate(parent, child) shouldBe Right(child)
        }
      }

      "fail for two random headers" in forAll(obftBlockHeaderGen, obftBlockHeaderGen) { (parent, child) =>
        validator.validate(parent, child) shouldBe Left(ParentHashError(parent, child))
      }

      "fail if numbers are not consecutive" in forAll(obftBlockHeaderGen) { parent =>
        forAll(obftHeaderChildGen(parent), Gen.choose[BigInt](Int.MinValue, parent.number.value - 1)) {
          (child, number) =>
            val modifiedChild = child.copy(number = BlockNumber(number))
            validator.validate(parent, modifiedChild) shouldBe Left(
              NumberError(parent, modifiedChild)
            )
        }
      }

      "fail if slots are not strictly increasing" in forAll(obftBlockHeaderGen) { parent =>
        forAll(obftHeaderChildGen(parent), Gen.choose(Long.MinValue, parent.slotNumber.number.toLong - 1)) {
          (child, slot) =>
            val modifiedChild = child.copy(slotNumber = Slot(slot))
            validator.validate(parent, modifiedChild) shouldBe Left(
              SlotNumberError(parent, modifiedChild)
            )
        }
      }

      "fail if timestamps are not strictly increasing" in forAll(obftBlockHeaderGen) { parent =>
        forAll(obftHeaderChildGen(parent), Gen.choose(Long.MinValue, parent.unixTimestamp.millis - 1)) {
          (child, time) =>
            val modifiedChild = child.copy(unixTimestamp = time.millisToTs)
            validator.validate(parent, modifiedChild) shouldBe Left(
              TimestampError(parent, modifiedChild)
            )
        }
      }

      "fail if gasLimit is lower than minimum" in forAll(obftBlockHeaderGen) { parent =>
        forAll(obftHeaderChildGen(parent)) { child =>
          val modifiedChild = child.copy(gasLimit = MinGasLimit - 1)
          validator.validate(parent, modifiedChild) shouldBe Left(
            GasLimitError(parent, modifiedChild)
          )
        }
      }

      "fail if ratio of gasLimits is bigger than 1024" in forAll(obftBlockHeaderGen) { parent =>
        forAll(obftHeaderChildGen(parent)) { child =>
          val modifiedChild = child.copy(gasLimit = parent.gasLimit * 1025)
          validator.validate(parent, modifiedChild) shouldBe Left(
            GasLimitError(parent, modifiedChild)
          )
        }
      }
    }
  }
}
