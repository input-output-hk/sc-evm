package io.iohk.scevm.plutus

import cats.Show
import io.bullet.borer
import io.bullet.borer.{DataItem, Tag}
import io.iohk.bytes.{ByteString, _}
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.plutus.ByteStringDatum.byteStringDatumShow
import io.iohk.scevm.plutus.ConstructorDatum.constructorDatumShow
import io.iohk.scevm.plutus.DatumMapItem.datumMapItemShow
import io.iohk.scevm.plutus.IntegerDatum.integerDatumShow
import io.iohk.scevm.plutus.ListDatum.listDatumShow
import io.iohk.scevm.plutus.MapDatum.mapDatumShow

// see https://github.com/input-output-hk/plutus/blob/b4adeb6cb5d4fbb4f94cf2d65e6ac961534c4fcd/plutus-core/plutus-core/src/PlutusCore/Data.hs#L33-L40
// data Data =
//      Constr Integer [Data]
//    | Map [(Data, Data)]
//    | List [Data]
//    | I Integer
//    | B BS.ByteString

/** Low level representation of redeemers on cardano. They usually map to a higher level haskell type.
  * We receive them as JSON and use [[Datum]] as an intermediary representation when mapping to a scala type
  */
sealed trait Datum

final case class ConstructorDatum(constructor: Int, fields: Vector[Datum]) extends Datum {
  override def toString: String = constructorDatumShow.show(this)
}
object ConstructorDatum {
  implicit lazy val constructorDatumShow: Show[ConstructorDatum] = cats.derived.semiauto.show

}

final case class MapDatum(map: Vector[DatumMapItem]) extends Datum {
  override def toString: String = mapDatumShow.show(this)
}
object MapDatum {
  implicit lazy val mapDatumShow: Show[MapDatum] = cats.derived.semiauto.show
}

final case class ListDatum(list: Vector[Datum]) extends Datum {
  override def toString: String = listDatumShow.show(this)
}
object ListDatum {
  implicit lazy val listDatumShow: Show[ListDatum] = cats.derived.semiauto.show
}

final case class IntegerDatum(int: BigInt) extends Datum {
  override def toString: String = integerDatumShow.show(this)
}
object IntegerDatum {
  implicit lazy val integerDatumShow: Show[IntegerDatum] = cats.derived.semiauto.show
}

final case class ByteStringDatum(bytes: ByteString) extends Datum {
  override def toString: String = byteStringDatumShow.show(this)
}
object ByteStringDatum {
  implicit lazy val byteStringDatumShow: Show[ByteStringDatum] = cats.derived.semiauto.show

}

final case class DatumMapItem(k: Datum, v: Datum) {
  override def toString: String = datumMapItemShow.show(this)
}
object DatumMapItem {
  implicit lazy val datumMapItemShow: Show[DatumMapItem] = cats.derived.semiauto.show
}

object Datum {
  implicit lazy val datumShow: Show[Datum] = cats.derived.semiauto.show
  // This is a port of
  // https://github.com/input-output-hk/plutus/blob/0bdd5d52bc2550c44f07d9fa068f9c817d00efc9/plutus-core/plutus-core/src/PlutusCore/Data.hs#L113

  /**  If it's less than 64 bytes use standard cbor encoding for long.
    *  Otherwise, it would be encoded as a bignum anyway, so we manually do the bignum
    *  encoding with a bytestring inside.
    *
    *  see https://github.com/input-output-hk/plutus/blob/0bdd5d52bc2550c44f07d9fa068f9c817d00efc9/plutus-core/plutus-core/src/PlutusCore/Data.hs#L131
    */
  implicit val integerDatumCborEncoder: borer.Encoder[IntegerDatum] = { (writer, integer) =>
    if (integer.int.isValidLong) {
      writer.writeLong(integer.int.longValue)
    } else if (integer.int >= 0) {
      writer
        .writeTag(Tag.PositiveBigNum)
        .write[ByteStringDatum](ByteStringDatum(ByteString(ByteUtils.bigIntToUnsignedByteArray(integer.int))))
    } else {
      writer
        .writeTag(Tag.NegativeBigNum)
        .write[ByteStringDatum](ByteStringDatum(ByteString(ByteUtils.bigIntToUnsignedByteArray(integer.int.abs))))
    }
  }

  implicit val integerDatumCborDecoder: borer.Decoder[IntegerDatum] = { reader =>
    if (reader.hasInt) {
      IntegerDatum(BigInt.int2bigInt(reader.readInt()))
    } else if (reader.hasLong) {
      IntegerDatum(BigInt.long2bigInt(reader.readLong()))
    } else if (reader.hasTag(Tag.PositiveBigNum)) {
      reader.readTag(Tag.PositiveBigNum)
      val datum = reader.read[ByteStringDatum]()
      IntegerDatum(BigInt(1, datum.bytes.toArray))
    } else if (reader.hasTag(Tag.NegativeBigNum)) {
      reader.readTag(Tag.NegativeBigNum)
      val datum = reader.read[ByteStringDatum]()
      IntegerDatum(BigInt(-1, datum.bytes.toArray))
    } else {
      reader.unexpectedDataItem("Long or PositiveNumberTag or NegativeNumberTag")
    }
  }

  // https://github.com/input-output-hk/plutus/blob/0bdd5d52bc2550c44f07d9fa068f9c817d00efc9/plutus-core/plutus-core/src/PlutusCore/Data.hs#L156
  implicit val byteStringDatumCborEncoder: borer.Encoder[ByteStringDatum] = borer.Encoder { (writer, byteString) =>
    val sizeLimit = 64
    if (byteString.bytes.length <= sizeLimit) {
      writer.writeBytes(byteString.bytes.toArray[Byte])
    } else {
      writer.writeBytesIterator(byteString.bytes.grouped(sizeLimit).map(_.toArray[Byte]))
    }
  }

  implicit val byteStringDatumDecoder: borer.Decoder[ByteStringDatum] = borer.Decoder { reader =>
    if (reader.hasBytesStart) {
      reader.readBytesStart()
      val readBytes =
        reader.readUntilBreak[ByteString](ByteString.empty)(acc => acc ++ ByteString(reader.readBytes[Array[Byte]]()))
      ByteStringDatum(ByteString(readBytes))
    } else {
      val readBytes = reader.readBytes[Array[Byte]]()
      ByteStringDatum(ByteString(readBytes))
    }

  }

  /** - Alternatives 0-6 -> tags 121-127, followed by the arguments in a list
    * - Alternatives 7-127 -> tags 1280-1400, followed by the arguments in a list
    * - Any alternatives, including those that don't fit in the above -> tag 102 followed by a list containing
    * an unsigned integer for the actual alternative, and then the arguments in a (nested!) list.
    *
    * see https://github.com/input-output-hk/plutus/blob/b4adeb6cb5d4fbb4f94cf2d65e6ac961534c4fcd/plutus-core/plutus-core/src/PlutusCore/Data.hs#L108
    */
  implicit val constructorDatumCborEncoder: borer.Encoder[ConstructorDatum] = borer.Encoder { (writer, constr) =>
    // borer writes IndexedSeq differently to LinearSeq.
    // Plutus core borer serialization uses LinearSeq, so Vector is converted to List, so borer uses LinearSeq encoder.
    val fields = constr.fields.toList
    if (constr.constructor >= 0 && constr.constructor < 7) {
      writer.writeTag(CborDatumTags.constructorSmall(constr.constructor)).write(fields)
    } else if (constr.constructor >= 7 && constr.constructor < 128) {
      writer.writeTag(CborDatumTags.constructorBig(constr.constructor)).write(fields)
    } else {
      writer
        .writeTag(CborDatumTags.ConstructorOverLimit)
        .writeToArray(constr.constructor, fields)
    }
  }

  implicit val constructorDatumCborDecoder: borer.Decoder[ConstructorDatum] = borer.Decoder { reader =>
    reader.readTag() match {
      case Tag.Other(t) if CborDatumTags.isConstructorSmall(t) =>
        val constructor = t - 121
        ConstructorDatum(constructor.toInt, reader.read[Seq[Datum]]().toVector)
      case Tag.Other(t) if CborDatumTags.isConstructorBig(t) =>
        val constructor = t - 1280 + 7
        ConstructorDatum(constructor.toInt, reader.read[Seq[Datum]]().toVector)
      // scalastyle:off magic.number
      case Tag.Other(120) =>
        throw new UnsupportedOperationException("Extended constructors are not supported")
      // scalastyle:on magic.number
      case t =>
        throw new IllegalArgumentException(s"Unrecognized tag $t")
    }
  }

  implicit val datumBorerCborEncoder: borer.Encoder[Datum] = borer.Encoder { (writer, datum) =>
    datum match {
      case v: ConstructorDatum => writer.write(v)
      case v: MapDatum         => writer.write(v)
      case v: ListDatum        => writer.write(v)
      case v: IntegerDatum     => writer.write(v)
      case v: ByteStringDatum  => writer.write(v)
    }
  }

  implicit val datumBorerCborDecoder: borer.Decoder[Datum] = borer.Decoder { reader =>
    if (
      reader.has(DataItem.Long) || reader.hasTag(Tag.NegativeBigNum) || reader
        .hasTag(Tag.PositiveBigNum) || reader.has(DataItem.Int)
    ) {
      reader.read[IntegerDatum]
    } else if (reader.hasMapStart) {
      reader.read[MapDatum]
    } else if (reader.hasArrayHeader || reader.hasArrayStart) {
      reader.read[ListDatum]
    } else if (reader.hasTag) {
      reader.read[ConstructorDatum]
    } else {
      reader.read[ByteStringDatum]
    }
  }

  implicit val mapDatumCborEncoder: borer.Encoder[MapDatum] =
    borer.Encoder[Map[Datum, Datum]].contramap(_.map.map { case DatumMapItem(k, v) => k -> v }.toMap)
  implicit val mapDatumCborDecoder: borer.Decoder[MapDatum] =
    borer
      .Decoder[Map[Datum, Datum]]
      .map(m => MapDatum(m.map { case (datum, datum1) => DatumMapItem(datum, datum1) }.toVector))

  // borer writes Vector differently to List, so explicit .toList is mandatory
  implicit val listDatumCborEncoder: borer.Encoder[ListDatum] = borer.Encoder[Seq[Datum]].contramap(_.list.toList)
  implicit val listDatumCborDecoder: borer.Decoder[ListDatum] =
    borer.Decoder[Seq[Datum]].map(ds => ListDatum.apply(ds.toVector))

  // scalastyle:off magic.number
  private object CborDatumTags {
    def constructorSmall(value: Int): Tag.Other = Tag.Other(121 + value)
    def constructorBig(value: Int): Tag.Other   = Tag.Other(1280 + value - 7)
    val ConstructorOverLimit: Tag.Other         = Tag.Other(102)

    def isConstructorSmall(t: Long): Boolean = t >= 121 && t < 128
    def isConstructorBig(t: Long): Boolean   = 1280 <= t && t < 1401
  }
}
