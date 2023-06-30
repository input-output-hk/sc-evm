package io.iohk.scevm.exec.vm

import io.iohk.scevm.testing.Generators._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class RevertReasonSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "RevertReason.decode" should {
    "should return human readable message" in {
      RevertReason.decode(notEnoughEtherCode) shouldBe "Not enough Ether provided."
    }

    "should be able to decode long error messages" in {
      RevertReason.decode(
        longErrorCode
      ) shouldBe "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
    }

    "should be able to decode arbitrary error messages" in {
      forAll(randomSizeByteArrayGen(1, 1000)) { bytes =>
        val message              = bytes.mkString
        val encoded: Array[Byte] = RevertReason.encode(message)
        val decoded              = RevertReason.decode(encoded)

        decoded shouldBe message
      }
    }
  }

  lazy val notEnoughEtherCode: Array[Byte] = BigInt(
    "08c379a0" +
      "0000000000000000000000000000000000000000000000000000000000000020" +
      "000000000000000000000000000000000000000000000000000000000000001a" +
      "4e6f7420656e6f7567682045746865722070726f76696465642e000000000000",
    16
  ).toByteArray

  lazy val longErrorCode: Array[Byte] = BigInt(
    "08c379a0" +
      "0000000000000000000000000000000000000000000000000000000000000020" +
      "00000000000000000000000000000000000000000000000000000000000000e7" +
      "4c6f72656d20697073756d20646f6c6f722073697420616d65742c20636f6e73" +
      "656374657475722061646970697363696e6720656c69742c2073656420646f20" +
      "656975736d6f642074656d706f7220696e6369646964756e74207574206c6162" +
      "6f726520657420646f6c6f7265206d61676e6120616c697175612e2055742065" +
      "6e696d206164206d696e696d2076656e69616d2c2071756973206e6f73747275" +
      "6420657865726369746174696f6e20756c6c616d636f206c61626f726973206e" +
      "69736920757420616c697175697020657820656120636f6d6d6f646f20636f6e" +
      "7365717561742e00000000000000000000000000000000000000000000000000",
    16
  ).toByteArray

}
