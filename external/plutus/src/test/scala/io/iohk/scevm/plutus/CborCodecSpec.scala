package io.iohk.scevm.plutus

import io.bullet.borer
import io.bullet.borer._
import io.iohk.bytes.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import Datum._
class CborCodecSpec extends AnyWordSpec with ScalaCheckPropertyChecks {
  val bytestringDatumGen: Gen[ByteStringDatum] = Gen.asciiPrintableStr.map(str => ByteStringDatum(ByteString(str)))
  val integerDatumGen: Gen[IntegerDatum]       = Gen.chooseNum(Long.MinValue, Long.MaxValue).map(num => IntegerDatum(num))
  val negBigIntGen: Gen[BigInt]                = Gen.chooseNum(BigInt(Long.MinValue) << 2, BigInt(Long.MinValue))
  val posBigIntGen: Gen[BigInt]                = Gen.chooseNum(BigInt(Long.MaxValue), BigInt(Long.MaxValue) << 2)
  val bigIntegerDatumGen: Gen[IntegerDatum]    = Gen.oneOf(negBigIntGen, posBigIntGen).map(num => IntegerDatum(num))
  val basicTypeDatum: Gen[Datum]               = Gen.oneOf(bytestringDatumGen, integerDatumGen, bigIntegerDatumGen)

  def genVector[T](gen: Gen[T]): Gen[Vector[T]]               = Gen.listOf(gen).map(_.toVector)
  def genVectorOfN[T](size: Int, gen: Gen[T]): Gen[Vector[T]] = Gen.listOfN(size, gen).map(_.toVector)
  def listDatum: Gen[ListDatum]                               = genVector(genDatum).map(list => ListDatum(list))
  def genDatum: Gen[Datum] = Gen.chooseNum(1, 100).flatMap { num =>
    if (num >= 95) constructorDatum else basicTypeDatum
  }
  def constructorDatum: Gen[ConstructorDatum] = for {
    tag    <- Gen.chooseNum(0, 127)
    fields <- genVectorOfN(10, basicTypeDatum)
  } yield ConstructorDatum(tag, fields)

  "bytestring datum" in {
    forAll(bytestringDatumGen)(roundtrip[ByteStringDatum]((a, b) => assert(a == Right(b))))
  }

  "integer datum" in {
    forAll(integerDatumGen)(roundtrip[IntegerDatum]((a, b) => assert(a == Right(b))))
  }

  "integer datum with big ints" in {
    forAll(bigIntegerDatumGen)(roundtrip[IntegerDatum]((a, b) => assert(a == Right(b))))
  }

  "list of ints" in {
    forAll(genVector(integerDatumGen).map(list => ListDatum(list)))(
      roundtrip[ListDatum]((a, b) => assert(a == Right(b)))
    )
  }

  "list of bytestrings" in {
    forAll(genVector(bytestringDatumGen).map(list => ListDatum(list)))(
      roundtrip[ListDatum]((a, b) => assert(a == Right(b)))
    )
  }

  "list of big ints" in {
    forAll(genVector(bigIntegerDatumGen).map(list => ListDatum(list)))(
      roundtrip[ListDatum]((a, b) => assert(a == Right(b)))
    )
  }

  "map of integers" in {
    val value = integerDatumGen.flatMap(i => integerDatumGen.map(bs => DatumMapItem(i, bs)))
    forAll(genVector(value).map(MapDatum.apply))(
      roundtrip[MapDatum]((a, b) =>
        assert(a.map(m => m.map.map(di => di.k -> di.v).toMap) == Right(b.map.map(di => di.k -> di.v).toMap))
      )
    )
  }

  "map of bytestrings" in {
    val value = bytestringDatumGen.flatMap(i => bytestringDatumGen.map(bs => DatumMapItem(i, bs)))
    forAll(genVector(value).map(MapDatum.apply))(
      roundtrip[MapDatum]((a, b) =>
        assert(a.map(m => m.map.map(di => di.k -> di.v).toMap) == Right(b.map.map(di => di.k -> di.v).toMap))
      )
    )
  }

  "small constructor" in {
    forAll(Gen.zip(bytestringDatumGen, integerDatumGen).map(p => ConstructorDatum(0, Vector(p._1, p._2))))(
      roundtrip[ConstructorDatum]((a, b) => assert(a == Right(b)))
    )
  }

  "big constructor" in {
    forAll(
      Gen
        .zip(
          bytestringDatumGen,
          integerDatumGen,
          integerDatumGen,
          integerDatumGen,
          integerDatumGen,
          integerDatumGen,
          integerDatumGen,
          integerDatumGen
        )
        .map(p => ConstructorDatum(0, p.productIterator.map(_.asInstanceOf[Datum]).toVector))
    )(
      roundtrip[ConstructorDatum]((a, b) => assert(a == Right(b)))
    )
  }

  "custom constructor tag constructor" in {
    forAll(
      Gen
        .zip(Gen.chooseNum(0, 127), bytestringDatumGen, integerDatumGen)
        .map(p => ConstructorDatum(p._1, Vector(p._2, p._3)))
    )(
      roundtrip[ConstructorDatum]((a, b) => assert(a == Right(b)))
    )
  }

  "arbitrary datum" in {
    forAll(listDatum)(roundtrip[Datum]((a, b) => assert(a == Right(b))))
  }

  "list of dynamic items" in {
    forAll(listDatum)(roundtrip[Datum]((a, b) => assert(a == Right(b))))
  }
  private def roundtrip[T: borer.Encoder: borer.Decoder](
      assertion: (Either[borer.Borer.Error[Input.Position], T], T) => Assertion
  )(
      value: T
  ) = {
    val encoded = Cbor.encode[T](value).toByteArray
    val decoded = Cbor.decode[Array[Byte]](encoded).to[T].valueEither
    assertion(decoded, value)
  }
}
