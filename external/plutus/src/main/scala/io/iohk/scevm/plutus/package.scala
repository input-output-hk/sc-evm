package io.iohk.scevm

import cats.Applicative
import cats.data.NonEmptyList
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.plutus.DatumDecoder.{DatumDecodeError, DatumOpsFromDatum}

import java.nio.charset.StandardCharsets

package object plutus {
  implicit val intDatumEncoder: DatumCodec[Int] = DatumCodec.make[Int](
    (value: Int) => IntegerDatum(value),
    _.asInteger
      .filterOrElse(_.isValidInt, DatumDecodeError("integer value out of range"))
      .map(_.intValue)
  )
  implicit val longDatumEncoder: DatumCodec[Long] = DatumCodec.make[Long](
    (value: Long) => IntegerDatum(value),
    _.asInteger
      .filterOrElse(_.isValidLong, DatumDecodeError("long value out of range"))
      .map(_.longValue)
  )
  implicit val bigIntDatumEncoder: DatumCodec[BigInt] = DatumCodec.make[BigInt](
    (value: BigInt) => IntegerDatum(value),
    _.asInteger
  )
  implicit val byteStringDatumEncoder: DatumCodec[ByteString] =
    DatumCodec.make[ByteString]((value: ByteString) => ByteStringDatum(value), _.asBytes)

  implicit val stringDatumCodec: DatumCodec[String] =
    DatumCodec[ByteString].imap(bs => new String(bs.toArray, StandardCharsets.UTF_8))(str => ByteString(str))

  implicit def listDatumEncoder[T: DatumCodec]: DatumCodec[List[T]] = DatumCodec.make[List[T]](
    (value: List[T]) => ListDatum(value.toVector.map(DatumEncoder[T].encode)),
    (datum: Datum) => datum.asList.flatMap(ld => ld.list.toList.traverse(DatumDecoder[T].decode))
  )

  implicit def nonEmptyListDatumEncoder[T: DatumCodec]: DatumCodec[NonEmptyList[T]] =
    DatumCodec[List[T]].imapEither(l =>
      NonEmptyList.fromList(l).toRight(DatumDecodeError("Cannot create non empty list from empty list"))
    )(_.toList)

  implicit def optionDatumEncoder[T: DatumCodec]: DatumCodec[Option[T]] = DatumCodec.make[Option[T]](
    {
      case Some(value) => ConstructorDatum(0, Vector(DatumEncoder[T].encode(value)))
      case None        => ConstructorDatum(1, Vector.empty)
    },
    {
      case ConstructorDatum(0, v) if v.size == 1 => DatumDecoder[T].decode(v.head).map(Some(_))
      case ConstructorDatum(1, v) if v.isEmpty   => Right(None)
      case _                                     => Left(DatumDecodeError("Invalid datum"))
    }
  )

  implicit def mapDecoder[K: DatumCodec, V: DatumCodec]: DatumCodec[Map[K, V]] = DatumCodec.make[Map[K, V]](
    map =>
      MapDatum(map.toVector.map { case (k, v) => DatumMapItem(DatumEncoder[K].encode(k), DatumEncoder[V].encode(v)) }),
    { (datum: Datum) =>
      type E[A] = Either[DatumDecodeError, A]
      datum.asMap.flatMap(mapDatum =>
        mapDatum.map
          .traverse { datumMapItem =>
            Applicative[E].product(DatumDecoder[K].decode(datumMapItem.k), DatumDecoder[V].decode(datumMapItem.v))
          }
          .map(_.toMap)
      )
    }
  )
}
