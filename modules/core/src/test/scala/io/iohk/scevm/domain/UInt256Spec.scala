package io.iohk.scevm.domain

import io.iohk.scevm.testing.Generators.{intGen, uInt256Gen}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class UInt256Spec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {
  "UInt256" when {
    "getByte with n < UInt256.Size should return n-th byte" in {
      forAll(uInt256Gen, intGen(0, UInt256.Size - 1)) { (value, n) =>
        val expectedByte = value.bytes(n)
        val actualByte   = value.getByte(n).toByte
        expectedByte shouldBe actualByte
      }
    }

    "getByte with n >= UInt256.Size should return 0" in {
      forAll(uInt256Gen, intGen(UInt256.Size, Int.MaxValue)) { (value, n) =>
        value.getByte(n).toByte shouldBe 0
      }
    }
  }

}
