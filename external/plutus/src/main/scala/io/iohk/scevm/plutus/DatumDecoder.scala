package io.iohk.scevm.plutus

import cats.syntax.all._
import cats.{FlatMap, Show}
import io.iohk.bytes.ByteString
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.generic.DatumDecoderDerivation

import scala.annotation.{implicitNotFound, tailrec}

/** This typeclass allow to transform a Datum to a higher level type.
  * As a [[Datum]] is parseable from a json string, a type that has a
  * datumDecoder can also be parsed from a String coming from db-sync.
  */
@implicitNotFound("Could not find implicit DatumDecoder for ${T}")
trait DatumDecoder[T] { self =>

  def decode(datum: Datum): Either[DatumDecodeError, T]
}

object DatumDecoder extends DatumDecoderDerivation {

  implicit val DatumDecoderFlatMap: FlatMap[DatumDecoder] = new FlatMap[DatumDecoder] {
    override def flatMap[A, B](fa: DatumDecoder[A])(
        f: A => DatumDecoder[B]
    ): DatumDecoder[B] = { (datum: Datum) =>
      fa.decode(datum) match {
        case Left(value)  => Left(value)
        case Right(value) => f(value).decode(datum)
      }
    }

    override def tailRecM[A, B](a: A)(
        f: A => DatumDecoder[Either[A, B]]
    ): DatumDecoder[B] = {
      @tailrec
      def go(da: DatumDecoder[Either[A, B]], datum: Datum): Either[DatumDecodeError, B] =
        da.decode(datum) match {
          case Left(value)         => Left(value)
          case Right(Right(value)) => Right(value)
          case Right(Left(_))      => go(f(a), datum)
        }

      (datum: Datum) => go(f(a), datum)
    }

    override def map[A, B](fa: DatumDecoder[A])(f: A => B): DatumDecoder[B] = (datum: Datum) => fa.decode(datum).map(f)
  }

  def apply[T](implicit dec: DatumDecoder[T]): DatumDecoder[T] = dec

  implicit def fromCodec[T](implicit codec: DatumCodec[T]): DatumDecoder[T] = codec.decoder

  def fromEither[T](either: Either[DatumDecodeError, T]): DatumDecoder[T] = (_: Datum) => either

  final case class DatumDecodeError(message: String)
  object DatumDecodeError {
    implicit val show: Show[DatumDecodeError] = Show.fromToString
  }

  trait DatumOps {
    def asConstructor(tag: Int): Either[DatumDecodeError, ConstructorDatum]
    def asBytes: Either[DatumDecodeError, ByteString]
    def asInteger: Either[DatumDecodeError, BigInt]
    def as[T: DatumDecoder]: Either[DatumDecodeError, T]
  }

  implicit class DatumOpsFromDatum(datum: Datum) extends DatumOps {

    def asConstructor: Either[DatumDecodeError, ConstructorDatum] = datum match {
      case constr: ConstructorDatum => Right(constr)
      case _                        => Left(DatumDecodeError("Expected datum to be a constructor"))
    }

    def asConstructor(tag: Int): Either[DatumDecodeError, ConstructorDatum] = datum match {
      case constr @ ConstructorDatum(`tag`, _) => Right(constr)
      case ConstructorDatum(actualTag, _) =>
        Left(DatumDecodeError(s"Expected datum to be a constructor with tag $tag but was $actualTag"))
      case _ => Left(DatumDecodeError("Expected datum to be a constructor"))
    }

    def asBytes: Either[DatumDecodeError, ByteString] = datum match {
      case ByteStringDatum(byteString) => Right(byteString)
      case _                           => Left(DatumDecodeError("Expected bytes datum"))
    }

    def asInteger: Either[DatumDecodeError, BigInt] = datum match {
      case IntegerDatum(value) => Right(value)
      case _                   => Left(DatumDecodeError("Expected bytes datum"))
    }

    def asMap: Either[DatumDecodeError, MapDatum] = datum match {
      case map @ MapDatum(_) => Right(map)
      case _                 => Left(DatumDecodeError("Expected map datum"))
    }

    def asList: Either[DatumDecodeError, ListDatum] = datum match {
      case list @ ListDatum(_) => Right(list)
      case _                   => Left(DatumDecodeError("Expected list datum"))
    }

    def as[T: DatumDecoder]: Either[DatumDecodeError, T] = DatumDecoder[T].decode(datum)
  }

  implicit class MonadicDatumOps(monadicDatum: Either[DatumDecodeError, Datum]) extends DatumOps {
    override def asConstructor(tag: Int): Either[DatumDecodeError, ConstructorDatum] =
      monadicDatum.flatMap(_.asConstructor(tag))

    override def asBytes: Either[DatumDecodeError, ByteString] = monadicDatum.flatMap(_.asBytes)

    override def asInteger: Either[DatumDecodeError, BigInt] = monadicDatum.flatMap(_.asInteger)

    override def as[T: DatumDecoder]: Either[DatumDecodeError, T] = monadicDatum.flatMap(_.as[T])
  }

  implicit class DatumConstructorOps(datum: ConstructorDatum) {

    def getField(index: Int): Either[DatumDecodeError, Datum] = datum.fields.lift(index) match {
      case Some(value) => Right(value)
      case None        => Left(DatumDecodeError(s"No field at index $index"))
    }
  }

  implicit class DatumDecoderOps[T](datumDecoder: DatumDecoder[T]) {
    def mapEither[T2](f: T => Either[DatumDecodeError, T2]): DatumDecoder[T2] =
      datumDecoder.flatMap(t => DatumDecoder.fromEither(f(t)))

    /** Creates a new decoder that unwraps decoded value from a surrounding datum constructor
      */
    def unwrap: DatumDecoder[T] = (datum: Datum) =>
      for {
        constr <- datum.asConstructor(0)
        field  <- constr.getField(0)
        res    <- datumDecoder.decode(field)
      } yield res
  }
}
