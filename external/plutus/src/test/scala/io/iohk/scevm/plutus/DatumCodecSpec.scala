package io.iohk.scevm.plutus

import cats.data.NonEmptyList
import io.iohk.bytes.ByteString
import io.iohk.scevm.plutus.ExampleADT.Person
import org.scalacheck.Gen
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class DatumCodecSpec extends AnyWordSpec with ScalaCheckPropertyChecks {

  "roundtrip test - int" in {
    forAll(Gen.chooseNum(Int.MinValue, Int.MaxValue))(roundtrip[Int])
  }

  "roundtrip test - long" in {
    forAll(Gen.long)(roundtrip[Long])
  }

  "roundtrip test - bigint" in {
    forAll(Gen.oneOf(Gen.posNum[BigInt], Gen.negNum[BigInt]))(roundtrip[BigInt])
  }

  "roundtrip test - ByteString" in {
    forAll(Gen.asciiPrintableStr.map(str => ByteString(str)))(roundtrip[ByteString])
  }

  "roundtrip test - string" in {
    forAll(Gen.asciiPrintableStr)(roundtrip[String])
  }

  "roundtrip test - option" in {
    forAll(Gen.option(Gen.posNum[Int]))(roundtrip[Option[Int]])
  }

  "roundtrip test - list" in {
    forAll(Gen.listOf(Gen.posNum[Int]))(roundtrip[List[Int]])
  }

  "roundtrip test - non empty list" in {
    forAll(Gen.nonEmptyListOf(Gen.posNum[Int]).map(NonEmptyList.fromListUnsafe))(roundtrip[NonEmptyList[Int]])
  }

  "roundtrip test - map" in {
    forAll(Gen.mapOf(Gen.zip(Gen.posNum[Int], Gen.listOf(Gen.posNum[Int]))))(roundtrip[Map[Int, List[Int]]])
  }

  "roundtrip test - complex product" in {
    implicit val exampleDatumCodec: DatumCodec[Example] = DatumCodec.derive
    forAll(for {
      int        <- Gen.option(Gen.posNum[Int])
      str        <- Gen.asciiPrintableStr
      byteString <- Gen.asciiPrintableStr.map(str => ByteString(str))
      list       <- Gen.listOf(Gen.mapOf(Gen.zip(Gen.posNum[Int], Gen.long)))
    } yield Example(int, str, byteString, list))(roundtrip[Example])
  }

  "roundtrip test - adt" in {
    implicit val personDatumCodec: DatumCodec[ExampleADT.Person] = DatumCodec.derive
    implicit val orgDatumCodec: DatumCodec[ExampleADT.Org]       = DatumCodec.derive
    implicit val exampleADTDatumCodec: DatumCodec[ExampleADT]    = DatumCodec.derive

    val personGen: Gen[ExampleADT.Person] = for {
      name <- Gen.asciiPrintableStr
      age  <- Gen.posNum[Int]
    } yield ExampleADT.Person(name, age)

    val orgGen: Gen[ExampleADT.Org] = for {
      companyName <- Gen.option(Gen.asciiPrintableStr)
    } yield ExampleADT.Org(companyName)

    val exampleADTGen: Gen[ExampleADT] = Gen.oneOf(orgGen, personGen)

    forAll(exampleADTGen)(roundtrip[ExampleADT])
  }

  "should deserialize datum that has more fields than the model" in {
    implicit val personDatumCodec: DatumCodec[ExampleADT.Person] = DatumCodec.derive
    val datumWithAdditionalField =
      ConstructorDatum(0, Vector(ByteStringDatum(ByteString("name")), IntegerDatum(1), IntegerDatum(2)))
    val decodedValue = DatumDecoder[Person].decode(datumWithAdditionalField)
    assert(decodedValue == Right(Person("name", 1)))
  }

  private def roundtrip[T: DatumCodec](value: T) = {
    val encoded = DatumEncoder[T].encode(value)
    val decoded = DatumDecoder[T].decode(encoded)
    assert(decoded == Right(value))
  }
}
final case class Example(option: Option[Int], string: String, byteString: ByteString, list: List[Map[Int, Long]])

sealed trait ExampleADT
object ExampleADT {
  final case class Person(name: String, age: Int)   extends ExampleADT
  final case class Org(companyName: Option[String]) extends ExampleADT
}
