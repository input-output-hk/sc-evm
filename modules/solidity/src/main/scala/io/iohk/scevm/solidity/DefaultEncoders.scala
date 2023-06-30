package io.iohk.scevm.solidity

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, UInt256}
import io.iohk.scevm.solidity.SolidityAbi.WordSize

private[solidity] trait DefaultEncoders {
  implicit val UInt256Encoder: SolidityAbiEncoder[UInt256] =
    SolidityAbiEncoder[UInt256]((value: UInt256) => value.bytes)

  implicit val UInt8Encoder: SolidityAbiEncoder[UInt8] =
    SolidityAbiEncoder[UInt8]((value: UInt8) => value.value.bytes)

  implicit val BytesEncoder: SolidityAbiEncoder[ByteString] = SolidityAbiEncoder[ByteString] { (value: ByteString) =>
    val padding = (WordSize - value.size % WordSize) % WordSize
    UInt256(value.size).bytes ++ value ++ ByteString(Array.fill[Byte](padding)(0))
  }

  implicit val Bytes32Encoder: SolidityAbiEncoder[Bytes32] = SolidityAbiEncoder[Bytes32] { (value: Bytes32) =>
    value.value
  }

  implicit val AddressEncoder: SolidityAbiEncoder[Address] = SolidityAbiEncoder[Address] { (address: Address) =>
    address.toUInt256.bytes
  }

  implicit val StringEncoder: SolidityAbiEncoder[String] = SolidityAbiEncoder[String] { (value: String) =>
    val bytes   = value.getBytes("utf-8")
    val padding = (WordSize - bytes.size % WordSize) % WordSize
    UInt256(bytes.size).bytes ++ ByteString(bytes) ++ ByteString(Array.fill[Byte](padding)(0))
  }

  implicit def tuple1Encoder[T1](implicit enc: SolidityAbiEncoder[T1]): SolidityAbiEncoder[Tuple1[T1]] =
    tupleEncoder(Seq(enc))

  implicit def tuple2Encoder[T1, T2](implicit
      enc1: SolidityAbiEncoder[T1],
      enc2: SolidityAbiEncoder[T2]
  ): SolidityAbiEncoder[(T1, T2)] =
    tupleEncoder(Seq(enc1, enc2))

  implicit def tuple3Encoder[T1, T2, T3](implicit
      enc1: SolidityAbiEncoder[T1],
      enc2: SolidityAbiEncoder[T2],
      enc3: SolidityAbiEncoder[T3]
  ): SolidityAbiEncoder[(T1, T2, T3)] =
    tupleEncoder(Seq(enc1, enc2, enc3))

  implicit def tuple4Encoder[T1, T2, T3, T4](implicit
      enc1: SolidityAbiEncoder[T1],
      enc2: SolidityAbiEncoder[T2],
      enc3: SolidityAbiEncoder[T3],
      enc4: SolidityAbiEncoder[T4]
  ): SolidityAbiEncoder[(T1, T2, T3, T4)] = tupleEncoder(Seq(enc1, enc2, enc3, enc4))

  /** Helper to implement encoder for tuples
    * Note that the type safety cannot be ensured and so it is up to the
    * implementation to make sure that:
    *  - the seq of encoder/types is the same size as the tuple
    *  - the seq of encoders/types is for the same type as the tuple elements and at the same index
    */
  private def tupleEncoder[T <: Product with Serializable](encoders: Seq[SolidityAbiEncoder[_]]) =
    new SolidityAbiEncoder[T] {

      def encode(value: T): ByteString = {
        val baseOffset = encoders.map(enc => headSize(enc.underlyingType)).sum

        val iterator: Iterator[(Any, SolidityAbiEncoder[Any])] =
          value.productIterator.zip(encoders.asInstanceOf[Seq[SolidityAbiEncoder[Any]]])

        val (head, tail) = iterator.foldLeft((ByteString.empty, ByteString.empty)) { (acc, valueWithEncoder) =>
          val (head, tail)     = acc
          val (value, encoder) = valueWithEncoder

          if (encoder.underlyingType.isDynamicType) {
            (head ++ UInt256(baseOffset + tail.size).bytes, tail ++ encoder.encode(value))
          } else {
            (head ++ encoder.encode(value), tail)
          }
        }
        head ++ tail
      }

      override lazy val underlyingType: SolidityElementaryType[_] =
        SolidityElementaryType.tupleType(encoders.map(_.underlyingType))
    }

  private def headSize[T](t: SolidityElementaryType[T]): Int = t.fixedSize.getOrElse(WordSize)

  implicit def seqEncoder[T](implicit valueEncoder: SolidityAbiEncoder[T]): SolidityAbiEncoder[Seq[T]] =
    new SolidityAbiEncoder[Seq[T]] {
      override def encode(value: Seq[T]): ByteString = {
        val typeHeadSize = headSize(valueEncoder.underlyingType)
        val baseOffset   = typeHeadSize * value.size
        val length       = UInt256(value.size).bytes

        val arrayPayload = if (valueEncoder.underlyingType.isDynamicType) {
          val (head, tail) = value.foldLeft((ByteString.empty, ByteString.empty)) { case (acc, item) =>
            val (head, tail) = acc
            (head ++ UInt256(baseOffset + tail.size).bytes, tail ++ valueEncoder.encode(item))
          }
          head ++ tail
        } else {
          value.foldLeft(ByteString.empty) { case (acc, item) => acc ++ valueEncoder.encode(item) }
        }
        length ++ arrayPayload
      }

      override private[solidity] def underlyingType: SolidityElementaryType[_] =
        SolidityElementaryType.variableSizeArrayType(valueEncoder.underlyingType)
    }
}
