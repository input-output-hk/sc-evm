package io.iohk.ethereum.utils

import io.iohk.bytes.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.ArraySeq
import scala.util.{Failure, Success, Try}

import ByteStringUtils._

class ByteStringUtilsTest extends AnyWordSpec with Matchers {

  "ByteStringUtilsTest" should {

    "succeed parsing a valid hash string" in {
      val validHashString = "00FF00FF"
      val parsed          = Try(Hex.decodeUnsafe(validHashString))
      val expected        = ByteString(Array[Byte](0, -1, 0, -1))
      parsed shouldEqual Success(expected)
    }

    "fail parsing a valid hash string" in {
      val invalidHashString = "XXYYZZXX"
      val parsed            = Try(Hex.decodeUnsafe(invalidHashString))
      parsed shouldBe a[Failure[_]]
    }

    "concatByteStrings for simple bytestrings" in {
      val bs1                    = Hex.decodeUnsafe("0000")
      val bs2                    = Hex.decodeUnsafe("FFFF")
      val summarized: ByteString = bs1 ++ bs2

      val concatenated: ByteString = ByteStringUtils.concatByteStrings(bs1, bs2)
      summarized shouldEqual concatenated
    }

    "concatByteStrings for various argument types" in {
      val bs1       = Hex.decodeUnsafe("0000")
      val bs2       = Hex.decodeUnsafe("FFFF")
      val bs3: Byte = 2
      val bs4       = Array[Byte](3, 3)
      val bs5       = Array[Byte](4, 4)
      bs1 ++ bs2
      val concatenated: ByteString = ByteStringUtils.concatByteStrings(bs1, bs2, bs3, bs4, bs5)
      concatenated shouldEqual Hex.decodeUnsafe("0000FFFF0203030404")
    }

    "apply padding the same way seqOps does" in {
      val bsu = Hex.decodeUnsafe("0000FFFF")
      val seq = ArraySeq.unsafeWrapArray(bsu.toArray)
      bsu.padToByteString(3, 0) shouldEqual bsu // result is ByteString
      bsu.padTo(3, 0) shouldEqual seq           // result is Seq

      val longSeq = ArraySeq[Byte](0, 0, -1, -1, 1, 1)
      val longBsu = Hex.decodeUnsafe("0000FFFF0101")
      bsu.padToByteString(6, 1) shouldEqual longBsu
      bsu.padTo(6, 1) shouldEqual longSeq
    }

  }
}
