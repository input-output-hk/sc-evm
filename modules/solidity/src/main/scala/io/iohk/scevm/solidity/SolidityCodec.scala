package io.iohk.scevm.solidity

import io.iohk.bytes.ByteString

import scala.annotation.implicitNotFound

/** A type class to encode a type into solidity ABI.
  * @see [[https://docs.soliditylang.org/en/develop/abi-spec.html solidity ABI spec]]
  */
@implicitNotFound("Could not find or derive an implicit solidity ABI encoder for ${T}")
trait SolidityAbiEncoder[T] { self =>
  def encode(value: T): ByteString

  def contramap[U](transform: U => T): SolidityAbiEncoder[U] = new SolidityAbiEncoder[U] {
    override def encode(value: U): ByteString = self.encode(transform(value))

    override lazy val underlyingType: SolidityElementaryType[_] = self.underlyingType
  }

  private[solidity] def underlyingType: SolidityElementaryType[_]
}

object SolidityAbiEncoder extends DefaultEncoders {
  def apply[T: SolidityAbiEncoder]: SolidityAbiEncoder[T] = implicitly[SolidityAbiEncoder[T]]

  protected[solidity] def apply[T: SolidityElementaryType](_encode: T => ByteString): SolidityAbiEncoder[T] =
    new SolidityAbiEncoder[T] {
      override def encode(value: T): ByteString = _encode(value)

      override lazy val underlyingType: SolidityElementaryType[_] = SolidityElementaryType[T]
    }

  def to[BaseType: SolidityAbiEncoder, ComplexType](
      convert: ComplexType => BaseType
  ): SolidityAbiEncoder[ComplexType] = SolidityAbiEncoder[BaseType].contramap(convert)
}

/** A type class to decode a ByteString encoded according to the solidity ABI.
  * @see [[https://docs.soliditylang.org/en/develop/abi-spec.html solidity ABI spec]]
  */
@implicitNotFound("Could not find or derive an implicit solidity ABI decoder for ${T}")
trait SolidityAbiDecoder[T] { self =>

  /** decode the value from the start of the bytes.
    * @return the decoded value and the remaining bytes
    */
  def decode(bytes: ByteString): T

  def map[U](to: T => U): SolidityAbiDecoder[U] = new SolidityAbiDecoder[U] {
    override def decode(bytes: ByteString): U =
      to(self.decode(bytes))

    override def underlyingType: SolidityElementaryType[_] = self.underlyingType
  }

  private[solidity] def underlyingType: SolidityElementaryType[_]
}

object SolidityAbiDecoder extends DefaultDecoders {
  def apply[T: SolidityAbiDecoder]: SolidityAbiDecoder[T] = implicitly[SolidityAbiDecoder[T]]

  protected[solidity] def apply[T: SolidityElementaryType](_decode: ByteString => T): SolidityAbiDecoder[T] =
    new SolidityAbiDecoder[T] {
      override def decode(bytes: ByteString): T = _decode(bytes)

      override def underlyingType: SolidityElementaryType[_] = SolidityElementaryType[T]
    }

  def from[BaseType: SolidityAbiDecoder, ComplexType](
      convert: BaseType => ComplexType
  ): SolidityAbiDecoder[ComplexType] = SolidityAbiDecoder[BaseType].map(convert)

}
