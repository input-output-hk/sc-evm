package io.iohk.scevm.solidity

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, UInt256}
import io.iohk.scevm.solidity.SolidityAbi.WordSize

import scala.annotation.implicitNotFound

/** This type class encode information about a type that can be used in both encoding and decoding
  * solidity structures. It is use to know if a type has a dynamic size or not (the encoding is different
  * depending on the two cases) and what is the underlying elementary type.
  *
  * Note that is it a sealed trait because only the elementary type have an instance. Other types have to
  * be mapped from an elementary type so that the `canonicalType` is properly derived.
  *
  * @see [[https://docs.soliditylang.org/en/develop/abi-spec.html solidity ABI spec]]
  * @tparam T
  */
@implicitNotFound("Could not find or derive an implicit solidity ABI type for ${T}")
sealed private[solidity] trait SolidityElementaryType[T] { self =>

  /** True if this is a dynamic type as defined by the solidity ABI.
    * i.e. this will be true if the full size depends not only on the size but also on the value.
    *
    * @see https://docs.soliditylang.org/en/latest/abi-spec.html#formal-specification-of-the-encoding
    */
  def isDynamicType: Boolean = fixedSize.isEmpty

  /** The size of the type in memory if it can be guessed only via the type itself.
    * It is needed to decode tuples.
    *
    * @see https://docs.soliditylang.org/en/latest/abi-spec.html#formal-specification-of-the-encoding
    */
  def fixedSize: Option[Int]

  /** The canonical type name used to get the function selector.
    * It is different from the name of the struct itself.
    */
  def name: String
}

private[solidity] object SolidityElementaryType {

  def apply[T: SolidityElementaryType]: SolidityElementaryType[T] = implicitly[SolidityElementaryType[T]]

  implicit val uint256Type: SolidityElementaryType[UInt256] = new SolidityElementaryType[UInt256] {
    override def fixedSize: Option[Int] = Some(WordSize)
    override def name: String           = "uint256"
  }

  implicit val uint8Type: SolidityElementaryType[UInt8] = new SolidityElementaryType[UInt8] {
    override def fixedSize: Option[Int] = Some(WordSize)
    override def name: String           = "uint8"
  }

  implicit val bytesType: SolidityElementaryType[ByteString] = new SolidityElementaryType[ByteString] {
    override def fixedSize: Option[Int] = None
    override def name: String           = "bytes"
  }

  implicit val bytesType32: SolidityElementaryType[Bytes32] = new SolidityElementaryType[Bytes32] {
    override def fixedSize: Option[Int] = Some(WordSize)
    override def name: String           = "bytes32"
  }

  implicit val addressType: SolidityElementaryType[Address] = new SolidityElementaryType[Address] {

    override def fixedSize: Option[Int] = Some(WordSize)
    override def name: String           = "address"
  }

  implicit val stringType: SolidityElementaryType[String] = new SolidityElementaryType[String] {
    override def fixedSize: Option[Int] = None
    override def name: String           = "string"
  }

  implicit val boolType: SolidityElementaryType[Boolean] = new SolidityElementaryType[Boolean] {
    override def fixedSize: Option[Int] = Some(WordSize)
    override def name: String           = "bool"
  }

  implicit def tuple1Type[T1: SolidityElementaryType]: SolidityElementaryType[Tuple1[T1]] = tupleType(
    Seq(SolidityElementaryType[T1])
  )

  implicit def tuple2Type[T1: SolidityElementaryType, T2: SolidityElementaryType]: SolidityElementaryType[(T1, T2)] =
    tupleType(Seq(SolidityElementaryType[T1], SolidityElementaryType[T2]))

  implicit def tuple3Type[T1: SolidityElementaryType, T2: SolidityElementaryType, T3: SolidityElementaryType]
      : SolidityElementaryType[(T1, T2, T3)] =
    tupleType(Seq(SolidityElementaryType[T1], SolidityElementaryType[T2], SolidityElementaryType[T3]))

  implicit def tuple4Type[
      T1: SolidityElementaryType,
      T2: SolidityElementaryType,
      T3: SolidityElementaryType,
      T4: SolidityElementaryType
  ]: SolidityElementaryType[(T1, T2, T3, T4)] =
    tupleType(
      Seq(
        SolidityElementaryType[T1],
        SolidityElementaryType[T2],
        SolidityElementaryType[T3],
        SolidityElementaryType[T4]
      )
    )

  def tupleType[T](types: Seq[SolidityElementaryType[_]]): SolidityElementaryType[T] =
    new SolidityElementaryType[T] { self: SolidityElementaryType[_] =>
      def fixedSize: Option[Int] =
        types.foldLeft(Option(0))((size, t) => size.flatMap(s => t.fixedSize.map(_ + s)))
      def name: String = types.map(_.name).mkString("(", ",", ")")
    }

  implicit def variableSizeArrayType[T](implicit t: SolidityElementaryType[T]): SolidityElementaryType[Seq[T]] =
    new SolidityElementaryType[Seq[T]] {
      override def fixedSize: Option[Int] = None

      override def name: String = s"${t.name}[]"
    }

  def fixedSizeArrayType[T](length: Int)(implicit t: SolidityElementaryType[T]): SolidityElementaryType[Seq[T]] =
    new SolidityElementaryType[Seq[T]] {
      override def fixedSize: Option[Int] = t.fixedSize.map(_ * length)

      override def name: String = s"${t.name}[$length]"
    }

}
