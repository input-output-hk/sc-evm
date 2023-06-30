package io.iohk.scevm.ledger

import cats.Id
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.Slot
import io.iohk.scevm.testing.CryptoGenerators.ecdsaPublicKeyGen
import io.iohk.scevm.testing.Generators
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class RoundRobinLeaderElectionSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with EitherValues {

  "getPubKeyofSlot" when {

    "on empty validators list" should {
      "be None" in {
        forAll(Generators.bigIntGen) { slotNumber =>
          val validators = Vector.empty
          assert(getPubKeyOfSlot(validators, Slot(slotNumber)).isEmpty)
        }
      }
    }

    "on non empty validators list" should {
      "be circular" in {
        forAll(Gen.listOf(ecdsaPublicKeyGen), Generators.bigIntGen) { (validators, slotNumber) =>
          val roundSize = validators.length
          validators.zipWithIndex.foreach { case (_, index) =>
            val slot = Slot(slotNumber + index)

            val res = getPubKeyOfSlot(validators.toVector, slot)

            assert(res.isDefined)
            assert(res == getPubKeyOfSlot(validators.toVector, Slot(slot.number + roundSize)))
          }
        }
      }

      "be complete" in {
        forAll(Gen.listOf(ecdsaPublicKeyGen), Generators.bigIntGen) { (validators, slotNumber) =>
          val fullList = validators.zipWithIndex.map { case (_, index) =>
            getPubKeyOfSlot(validators.toVector, Slot(slotNumber + index))
          }

          assert(fullList.flatten.length == validators.length)
        }
      }
    }
  }

  private def getPubKeyOfSlot(validators: Vector[ECDSA.PublicKey], slot: Slot): Option[ECDSA.PublicKey] =
    new RoundRobinLeaderElection[Id](validators).getSlotLeader(slot).toOption
}
