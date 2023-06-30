package io.iohk.scevm.domain

import cats.Show
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.utils.ByteUtils.padLeft
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.serialization.Newtype

class Address private (val bytes: ByteString) extends AnyVal {

  def toArray: Array[Byte] = bytes.toArray

  def toUInt256: UInt256 = UInt256(bytes)

  override def toString: String =
    s"0x$toUnprefixedString"

  def toUnprefixedString: String =
    Hex.toHexString(toArray)

}

object Address {

  val Length                 = 20
  val FirstByteOfAddress     = 12
  val LastByteOfAddress: Int = FirstByteOfAddress + Length

  def apply(bytes: ByteString): Address = {
    val truncated = bytes.takeRight(Length)
    val extended  = padLeft(truncated, Length)
    new Address(extended)
  }

  def fromBytes(byteString: ByteString): Either[String, Address] =
    Either.cond(byteString.length == Length, Address(byteString), s"Invalid address: ${Hex.toHexString(byteString)}")

  def fromBytesUnsafe(byteString: ByteString): Address =
    fromBytes(byteString) match {
      case Left(error)  => throw new IllegalArgumentException(error)
      case Right(value) => value
    }

  def apply(uint: UInt256): Address = Address(uint.bytes)

  def apply(bigInt: BigInt): Address = Address(UInt256(bigInt))

  def apply(arr: Array[Byte]): Address = Address(ByteString(arr))

  def apply(addr: Long): Address = Address(UInt256(addr))

  def apply(hexString: String): Address = {
    val bytes = Hex.decodeUnsafe(hexString)
    require(bytes.nonEmpty && bytes.length <= Length, s"Invalid address: $hexString")
    Address(bytes)
  }

  def fromPublicKey(key: SidechainPublicKey): Address =
    Address(crypto.kec256(key.bytes).slice(FirstByteOfAddress, LastByteOfAddress))

  def fromPrivateKey(key: SidechainPrivateKey): Address = fromPublicKey(SidechainPublicKey.fromPrivateKey(key))

  import io.iohk.scevm.serialization.ByteArrayEncoder
  implicit val hashedAddressEncoder: ByteArrayEncoder[Address] = new ByteArrayEncoder[Address] {
    override def toBytes(addr: Address): Array[Byte] = crypto.kec256(addr.toArray)
  }

  implicit val valueClass: Newtype[Address, ByteString] = Newtype[Address, ByteString](Address(_), _.bytes)

  implicit val show: Show[Address] = Show[ByteString].contramap(_.bytes)
}
