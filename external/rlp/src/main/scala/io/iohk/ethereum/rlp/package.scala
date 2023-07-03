package io.iohk.ethereum

import io.iohk.ethereum.utils.Hex

import scala.reflect.ClassTag
import scala.util.control.NonFatal

package object rlp {

  /** An exception capturing a deserialization error.
    *
    * The `encodeables` are a stack of values as we recursed into the data structure
    * which may help deducting what went wrong. The last element is what caused the
    * problem but it may be easier to recognise if we look at the head.
    */
  final case class RLPException(message: String, encodeables: List[RLPEncodeable] = Nil)
      extends RuntimeException(message)
  object RLPException {
    def apply(message: String, encodeable: RLPEncodeable): RLPException =
      RLPException(message, List(encodeable))

    def decodeError[T](subject: String, error: String, encodeables: List[RLPEncodeable] = Nil): T =
      throw RLPException(s"Cannot decode $subject: $error", encodeables)
  }

  sealed trait RLPEncodeable {
    def decodeAs[T: RLPDecoder](subject: => String): T =
      tryDecode[T](subject, this)(RLPDecoder[T].decode)
  }

  final case class RLPList(items: RLPEncodeable*) extends RLPEncodeable {
    def +:(item: RLPEncodeable): RLPList =
      RLPList((item +: items): _*)

    def :+(item: RLPEncodeable): RLPList =
      RLPList((items :+ item): _*)

    def ++(other: RLPList): RLPList =
      RLPList((items ++ other.items): _*)
  }

  final case class RLPValue(bytes: Array[Byte]) extends RLPEncodeable {
    override def toString: String = s"RLPValue(${Hex.toHexString(bytes)})"
  }

  /** Modelise a RLPEncodable that should be binary prefixed by a raw byte.
    *
    * When converting this RLPEncodable to byte, the resulting value will be:
    * prefix || prefixedRLPEncodable.toByte
    * where || is the binary concatenation symbol.
    *
    * To be able to read back the data, use TypedTransaction.TypedTransactionsRLPAggregator
    *
    * This is for example used for typed transaction and typed receipt.
    *
    * @param prefix the raw byte
    * @param prefixedRLPEncodeable the RLPEncodable to prefix with
    */
  final case class PrefixedRLPEncodable(prefix: Byte, prefixedRLPEncodeable: RLPEncodeable) extends RLPEncodeable {
    require(prefix >= 0, "prefix should be in the range [0; 0x7f]")
  }

  trait RLPEncoder[T] {
    def encode(obj: T): RLPEncodeable
  }
  object RLPEncoder {
    def apply[T](implicit ev: RLPEncoder[T]): RLPEncoder[T] = ev

    implicit def identityInstance[T <: RLPEncodeable]: RLPEncoder[T] = instance(identity)

    def instance[T](f: T => RLPEncodeable): RLPEncoder[T] =
      new RLPEncoder[T] {
        override def encode(obj: T): RLPEncodeable = f(obj)
      }

    def encode[T: RLPEncoder](obj: T): RLPEncodeable =
      RLPEncoder[T].encode(obj)
  }

  trait RLPDecoder[T] {
    def decode(rlp: RLPEncodeable): T
  }
  object RLPDecoder {
    def apply[T](implicit ev: RLPDecoder[T]): RLPDecoder[T] = ev

    def instance[T](f: RLPEncodeable => T): RLPDecoder[T] =
      new RLPDecoder[T] {
        override def decode(rlp: RLPEncodeable): T = f(rlp)
      }

    def decode[T: RLPDecoder](rlp: RLPEncodeable): T =
      RLPDecoder[T].decode(rlp)
  }

  def encode[T](input: T)(implicit enc: RLPEncoder[T]): Array[Byte] = RLP.encode(enc.encode(input))

  def encode(input: RLPEncodeable): Array[Byte] = RLP.encode(input)

  def decode[T](data: Array[Byte])(implicit dec: RLPDecoder[T]): T = dec.decode(RLP.rawDecode(data))

  def decode[T](data: RLPEncodeable)(implicit dec: RLPDecoder[T]): T = dec.decode(data)

  def rawDecode(input: Array[Byte]): RLPEncodeable = RLP.rawDecode(input)

  def tryDecode[T](subject: => String, encodeable: RLPEncodeable)(f: RLPEncodeable => T): T =
    try f(encodeable)
    catch {
      case RLPException(message, encodeables) =>
        RLPException.decodeError(subject, message, encodeable :: encodeables)
      case NonFatal(ex) =>
        RLPException.decodeError(subject, ex.getMessage, List(encodeable))
    }

  /** This function calculates the next element item based on a previous element starting position. It's meant to be
    * used while decoding a stream of RLPEncoded Items.
    *
    * @param data Data with encoded items
    * @param pos  Where to start. This value should be a valid start element position in order to be able to calculate
    *             next one
    * @return Next item position
    * @throws RLPException if there is any error
    */
  def nextElementIndex(data: Array[Byte], pos: Int): Int = RLP.getItemBounds(data, pos).end + 1

  trait RLPSerializable {
    def toRLPEncodable: RLPEncodeable

    def toBytes: Array[Byte] = encode(this.toRLPEncodable)
  }

  type RLPCodec[T] = RLPEncoder[T] with RLPDecoder[T]

  object RLPCodec {
    def instance[T](enc: T => RLPEncodeable, dec: PartialFunction[RLPEncodeable, T])(implicit
        ct: ClassTag[T]
    ): RLPCodec[T] =
      new RLPEncoder[T] with RLPDecoder[T] {
        override def encode(obj: T): RLPEncodeable =
          enc(obj)

        override def decode(rlp: RLPEncodeable): T =
          if (dec.isDefinedAt(rlp)) dec(rlp)
          else RLPException.decodeError(s"type ${ct.runtimeClass.getSimpleName}", "Unexpected RLP.", List(rlp))
      }

    def apply[T](enc: RLPEncoder[T], dec: RLPDecoder[T]): RLPCodec[T] =
      new RLPEncoder[T] with RLPDecoder[T] {
        override def encode(obj: T): RLPEncodeable = enc.encode(obj)
        override def decode(rlp: RLPEncodeable): T = dec.decode(rlp)
      }

    implicit class Ops[A](val codec: RLPCodec[A]) extends AnyVal {

      /** Given a codec for type A, make a coded for type B. */
      def xmap[B](f: A => B, g: B => A): RLPCodec[B] =
        new RLPEncoder[B] with RLPDecoder[B] {
          override def encode(obj: B): RLPEncodeable = codec.encode(g(obj))
          override def decode(rlp: RLPEncodeable): B = f(codec.decode(rlp))
        }
    }
  }
}
