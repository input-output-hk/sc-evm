package io.iohk.ethereum.utils

import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class HexSpec extends AnyWordSpec with DiffShouldMatcher with ScalaCheckPropertyChecks {
  "decodeAsArrayUnsafe" should {
    "correctly parse strings with 0x prefix" in {
      val bigInt = BigInt(12345)
      Hex.decodeAsArrayUnsafe("0x" + bigInt.toString(16)).toSeq shouldMatchTo bigInt.toByteArray.toSeq
    }

    "correctly parse strings without 0x prefix" in {
      val bigInt = BigInt(12345)
      Hex.decodeAsArrayUnsafe(bigInt.toString(16)).toSeq shouldMatchTo bigInt.toByteArray.toSeq
    }

    "parse string with odd number of char" in {
      val inputs = Seq(
        "123abba",
        "0123abba",
        "0x123abba",
        "0x0123abba"
      )

      inputs.map { input =>
        // bytes 01, 23, ab, ba in "signed 2's complement" format
        val expected = Seq[Byte](1.toByte, 35.toByte, -85.toByte, -70.toByte)
        val parsed   = Hex.decodeAsArrayUnsafe(input).toSeq
        parsed shouldMatchTo expected
      }
    }
  }

  "toHexString" should {
    "correctly convert to string" in {
      val decoded = Hex.decodeUnsafe("123abba")
      Hex.toHexString(decoded) shouldMatchTo "0123abba"
    }
  }

  "parseHexNumber" should {
    "correctly parse hex strings with 0x prefix" in {
      forAll(Generators.bigIntGen) { input =>
        val hex = "0x" + input.toString(16)
        assert(Hex.parseHexNumberUnsafe(hex) == input)
      }
    }

    "correctly parse hex strings without 0x prefix" in {
      forAll(Generators.bigIntGen) { input =>
        val hex = input.toString(16)
        assert(Hex.parseHexNumberUnsafe(hex) == input)
      }
    }
  }
}
