package io.iohk.scevm.domain

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.RLPEncodeable
import io.iohk.ethereum.rlp.RLPImplicitConversions.bigIntFromEncodeable
import io.iohk.ethereum.utils.{ByteUtils, Hex}
import io.iohk.scevm.domain.BigIntUtils._
import io.iohk.scevm.serialization.Newtype

final case class Nonce(value: BigInt) extends AnyVal with Ordered[Nonce] {

  override def toString: String = s"Nonce(${value.toString})"

  def increaseOne: Nonce = Nonce(value + 1)

  def decreaseOne: Nonce = Nonce(value - 1)

  override def compare(that: Nonce): Int = this.value.compare(that.value)

  def bytes: ByteString = value.bytes(Nonce.Size)
}

object Nonce {
  implicit val show: Show[Nonce] = Show.fromToString

  // Size used for byte representation
  val Size: Int   = 32
  val Zero: Nonce = Nonce(0)

  implicit val valueClass: Newtype[Nonce, BigInt] = Newtype[Nonce, BigInt](Nonce.apply, _.value)

  def apply(array: Array[Byte]): Nonce = Nonce(ByteUtils.toBigInt(ByteString(array)))

  def apply(encodable: RLPEncodeable): Nonce = Nonce(bigIntFromEncodeable(encodable))

  def fromHexUnsafe(str: String): Nonce =
    Nonce(Hex.parseHexNumberUnsafe(str))
}
