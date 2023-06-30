package io.iohk.scevm.solidity

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.UInt256
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SolidityAbiSpec extends AnyWordSpec with Matchers {

  "SolidityAbi" should {
    "return the right function selectors" in {
      SolidityAbi.functionSelector[String]("lock") shouldBe ByteString(Hex.decodeAsArrayUnsafe("320a98fd"))
      SolidityAbi.functionSelector[UInt256]("outgoingTransactions") shouldBe ByteString(
        Hex.decodeAsArrayUnsafe("80329a66")
      )
    }

    "return the right payload" in {
      SolidityAbi.solidityCall("outgoingTransactions", UInt256(0)) shouldBe ByteString(
        Hex.decodeAsArrayUnsafe("80329a660000000000000000000000000000000000000000000000000000000000000000")
      )
    }

    "return the right payload for a dynamic argument" in {
      SolidityAbi.solidityCall("outgoingTransactions", ByteString(1)) shouldBe ByteString(
        Hex.decodeAsArrayUnsafe(
          "74a5be87" +
            "0000000000000000000000000000000000000000000000000000000000000020" + // offset of 1st argument in tuple
            "0000000000000000000000000000000000000000000000000000000000000001" + // size of the byte array
            "0100000000000000000000000000000000000000000000000000000000000000"   // 0x01 right padded
        )
      )
    }

  }
}
