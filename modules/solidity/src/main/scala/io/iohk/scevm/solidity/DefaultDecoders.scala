package io.iohk.scevm.solidity

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, UInt256}

import DefaultDecoders._
import SolidityAbi.WordSize

trait DefaultDecoders {

  implicit val UInt256Decoder: SolidityAbiDecoder[UInt256] =
    SolidityAbiDecoder(bytes => UInt256(bytes.take(WordSize)))

  implicit val UInt8Decoder: SolidityAbiDecoder[UInt8] =
    SolidityAbiDecoder(bytes => UInt8(UInt256(bytes.take(WordSize))))

  implicit val BytesDecoder: SolidityAbiDecoder[ByteString] = SolidityAbiDecoder { bytes =>
    val (sizeBytes, contentAndRemaining) = bytes.splitAt(WordSize)
    val size                             = UInt256(sizeBytes).toInt
    val content                          = contentAndRemaining.take(size)
    content

  }

  implicit val Bytes32Decoder: SolidityAbiDecoder[Bytes32] =
    SolidityAbiDecoder(bytes => Bytes32(bytes.take(WordSize)))

  implicit val AddressDecoder: SolidityAbiDecoder[Address] =
    SolidityAbiDecoder(bytes => Address(UInt256(bytes.take(WordSize))))

  implicit val StringDecoder: SolidityAbiDecoder[String] =
    BytesDecoder.map(bytes => new String(bytes.toArray))

  implicit val BoolDecoder: SolidityAbiDecoder[Boolean] =
    SolidityAbiDecoder(bytes => UInt256(bytes.take(WordSize)) != UInt256(0))

  implicit def tuple1Decoder[T1](implicit dec: SolidityAbiDecoder[T1]): SolidityAbiDecoder[Tuple1[T1]] =
    tupleDecoder(dec) { case Seq(v1) => Tuple1(v1.asInstanceOf[T1]) }

  implicit def tuple2Decoder[T1, T2](implicit
      dec1: SolidityAbiDecoder[T1],
      dec2: SolidityAbiDecoder[T2]
  ): SolidityAbiDecoder[(T1, T2)] =
    tupleDecoder(dec1, dec2) { case Seq(v1, v2) => (v1, v2).asInstanceOf[(T1, T2)] }

  implicit def tuple3Decoder[T1, T2, T3](implicit
      dec1: SolidityAbiDecoder[T1],
      dec2: SolidityAbiDecoder[T2],
      dec3: SolidityAbiDecoder[T3]
  ): SolidityAbiDecoder[(T1, T2, T3)] =
    tupleDecoder(dec1, dec2, dec3) { case Seq(v1, v2, v3) => (v1, v2, v3).asInstanceOf[(T1, T2, T3)] }

  implicit def tuple4Decoder[T1, T2, T3, T4](implicit
      dec1: SolidityAbiDecoder[T1],
      dec2: SolidityAbiDecoder[T2],
      dec3: SolidityAbiDecoder[T3],
      dec4: SolidityAbiDecoder[T4]
  ): SolidityAbiDecoder[(T1, T2, T3, T4)] =
    tupleDecoder(dec1, dec2, dec3, dec4) { case Seq(v1, v2, v3, v4) => (v1, v2, v3, v4).asInstanceOf[(T1, T2, T3, T4)] }

  /** Helper to implement decoders for tuples
    * Note that the type safety cannot be ensured and so it is up to the
    * implementation to make sure that:
    *  - the seq of encoder/types is the same size as the tuple
    *  - the seq of encoders/types is for the same type as the tuple elements and at the same index
    */
  private def tupleDecoder[T <: Product with Serializable](
      decoders: SolidityAbiDecoder[_]*
  )(seqToTuple: PartialFunction[Seq[_], T]) =
    new SolidityAbiDecoder[T] {
      override def decode(bytes: ByteString): T = {
        val (head, tail) = bytes.splitAt(decoders.map(headSize(_)).sum)

        val offsets = decoders.scanLeft(0)((offset, dec) => offset + headSize(dec))

        val values = decoders.zip(offsets).map { case (dec, offset) => decodeTupleElem(head, tail)(offset)(dec) }

        seqToTuple(values)
      }

      override private[solidity] def underlyingType =
        SolidityElementaryType.tupleType(decoders.map(_.underlyingType))
    }

  implicit def arrayAbiDecoder[T](implicit dec: SolidityAbiDecoder[T]): SolidityAbiDecoder[Seq[T]] =
    new SolidityAbiDecoder[Seq[T]] {
      override def decode(bytes: ByteString): Seq[T] = {

        val (sizeBytes, contentAndRemaining) = bytes.splitAt(WordSize)
        val size                             = UInt256(sizeBytes).toInt
        fixedSizeArrayAbiDecoder[T](size).decode(contentAndRemaining)
      }

      override def underlyingType: SolidityElementaryType[_] =
        SolidityElementaryType.variableSizeArrayType(dec.underlyingType)
    }

  def fixedSizeArrayAbiDecoder[T](
      length: Int
  )(implicit dec: SolidityAbiDecoder[T]): SolidityAbiDecoder[Seq[T]] =
    new SolidityAbiDecoder[Seq[T]] {

      override def decode(bytes: ByteString): Seq[T] =
        dec.underlyingType.fixedSize match {
          case Some(typeHeadSize) =>
            bytes.grouped(typeHeadSize).map(dec.decode).toSeq
          case None =>
            val (head, tail) = bytes.splitAt(WordSize * length)
            head
              .grouped(WordSize)
              .map(UInt256Decoder.decode(_).toInt)
              .map(offset => dec.decode(tail.drop(offset - head.length)))
              .toSeq
        }

      override def underlyingType: SolidityElementaryType[_] =
        SolidityElementaryType.fixedSizeArrayType(length)(dec.underlyingType)
    }

  private def decodeTupleElem[T](head: ByteString, tail: ByteString)(headOffset: Int)(implicit
      dec: SolidityAbiDecoder[T]
  ): T =
    if (dec.underlyingType.isDynamicType) {
      val offset: Int = UInt256Decoder.decode(head.drop(headOffset)).toInt
      dec.decode(tail.drop(offset - head.size))
    } else {
      dec.decode(head.drop(headOffset))
    }
}

object DefaultDecoders {

  private def headSize[T](implicit dec: SolidityAbiDecoder[T]): Int = dec.underlyingType.fixedSize.getOrElse(WordSize)

}
