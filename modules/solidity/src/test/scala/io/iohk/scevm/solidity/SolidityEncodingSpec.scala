package io.iohk.scevm.solidity

import io.iohk.scevm.domain.UInt256
import io.iohk.scevm.exec.vm.Generators
import org.scalacheck.Arbitrary
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import SolidityEncodingSpec._

class SolidityEncodingSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  // examples taken from https://docs.soliditylang.org/en/develop/abi-spec.html#examples

  "Solidity ABI codecs" should {

    "make a round trip with an int" in forAll { (v: Int) =>
      val wrapped = UInt256(v)
      SolidityAbiDecoder[UInt256].decode(SolidityAbiEncoder[UInt256].encode(wrapped)) shouldBe wrapped
    }

    "make a round trip with a uint8" in forAll { (v: Int) =>
      val wrapped = UInt8(v)
      SolidityAbiDecoder[UInt8].decode(SolidityAbiEncoder[UInt8].encode(wrapped)) shouldBe wrapped
    }

    "make a round trip with with an unicode char" in {
      val string = "abcde123🐍"
      SolidityAbiDecoder[String].decode(SolidityAbiEncoder[String].encode(string)) shouldBe string
    }

    "make a round trip with a string" in forAll { (string: String) =>
      SolidityAbiDecoder[String].decode(SolidityAbiEncoder[String].encode(string)) shouldBe string
    }

    "make a round trip with 32 bytes array" in forAll { (bs: Bytes32) =>
      SolidityAbiDecoder[Bytes32].decode(SolidityAbiEncoder[Bytes32].encode(bs)) shouldBe bs
    }

    "make a round trip with a dynamic tuple2" in forAll { (tuple: (String, UInt256)) =>
      SolidityAbiDecoder[(String, UInt256)].decode(
        SolidityAbiEncoder[(String, UInt256)].encode(tuple)
      ) shouldBe tuple
    }

    "make a round trip with a dynamic tuple3" in forAll { (tuple: (String, UInt256, String)) =>
      SolidityAbiDecoder[(String, UInt256, String)].decode(
        SolidityAbiEncoder[(String, UInt256, String)].encode(tuple)
      ) shouldBe tuple
    }

    "make a round trip with a dynamic tuple4" in forAll { (tuple: (String, UInt256, String, UInt256)) =>
      SolidityAbiDecoder[(String, UInt256, String, UInt256)].decode(
        SolidityAbiEncoder[(String, UInt256, String, UInt256)].encode(tuple)
      ) shouldBe tuple
    }

    "make a round trip with a case class" in forAll { (foo: String, bar: UInt256) =>
      val value = EncodingTestClass(foo, bar)
      SolidityAbiDecoder[EncodingTestClass].decode(
        SolidityAbiEncoder[EncodingTestClass].encode(value)
      ) shouldBe value
    }

    "make a round trip with a list of ints" in forAll { (v: List[Int]) =>
      val wrapped = v.map(UInt256.apply(_))
      SolidityAbiDecoder[Seq[UInt256]].decode(SolidityAbiEncoder[Seq[UInt256]].encode(wrapped)) shouldBe wrapped
    }

    "make a round trip with a list of dynamic tuple" in forAll { (list: List[(String, UInt256, String)]) =>
      SolidityAbiDecoder[Seq[(String, UInt256, String)]].decode(
        SolidityAbiEncoder[Seq[(String, UInt256, String)]].encode(list)
      ) shouldBe list
    }
  }
}

object SolidityEncodingSpec {
  implicit val uint256Arbitrary: Arbitrary[UInt256] = Arbitrary(Generators.getUInt256Gen())
  implicit val bytes32Gen: Arbitrary[Bytes32]       = Arbitrary(Generators.getUInt256Gen().map(i => Bytes32(i.bytes)))
  final case class EncodingTestClass(foo: String, bar: UInt256)
  implicit val testClassEncoder: SolidityAbiEncoder[EncodingTestClass] =
    SolidityAbiEncoder.to(EncodingTestClass.unapply(_).get)
  implicit val testClassDecoder: SolidityAbiDecoder[EncodingTestClass] =
    SolidityAbiDecoder.from((EncodingTestClass.apply _).tupled)
}
