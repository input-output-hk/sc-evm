package io.iohk.scevm.domain

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.{PrefixedRLPEncodable, RLPCodec, RLPEncodeable, RLPList, RLPValue}
import org.bouncycastle.util.encoders.Hex

import scala.annotation.nowarn

sealed trait Transaction extends Product with Serializable {
  def nonce: Nonce
  def maxPriorityFeePerGas: BigInt
  def maxFeePerGas: BigInt
  def gasLimit: BigInt
  def receivingAddress: Option[Address]
  def value: BigInt
  def payload: ByteString

  def isContractInit: Boolean = receivingAddress.isEmpty

  // Note that this does not implement eip-1559 and just compute the price the old way
  def calculateUpfrontGas: UInt256 = UInt256(gasLimit * maxFeePerGas)

  protected def receivingAddressString: String =
    receivingAddress.map(_.toString).getOrElse("[Contract creation]")

  protected def payloadString: String =
    s"${if (isContractInit) "ContractInit: " else "TransactionData: "}${Hex.toHexString(payload.toArray[Byte])}"
}

object Transaction {
  implicit val show: Show[Transaction] = cats.derived.semiauto.show

  val NonceLength = 32
  val GasLength   = 32
  val ValueLength = 32

  val LegacyThresholdLowerBound: Int = 0xc0
  val LegacyThresholdUpperBound: Int = 0xfe

  def accessList(tx: Transaction): List[AccessListItem] = tx match {
    case _: LegacyTransaction           => Nil
    case transaction: TransactionType01 => transaction.accessList
    case transaction: TransactionType02 => transaction.accessList
  }
}

sealed trait TypedTransaction extends Transaction {
  def chainId: BigInt
  def accessList: List[AccessListItem]

  def getTransactionType: Byte
}

object TypedTransaction {
  val Type01: Byte = 1.toByte
  val Type02: Byte = 2.toByte

  val MinAllowedType: Byte = 0x0
  val MaxAllowedType: Byte = 0x7f

  implicit class TransactionTypeValidator(val transactionType: Byte) extends AnyVal {
    def isValidTransactionType: Boolean = transactionType >= MinAllowedType && transactionType <= MaxAllowedType
  }

  implicit class ByteArrayTransactionTypeValidator(val binaryData: Array[Byte]) extends AnyVal {
    def isValidTransactionType: Boolean = binaryData.length == 1 && binaryData.head.isValidTransactionType
  }

  implicit class TransactionsRLPAggregator(val encodables: Seq[RLPEncodeable]) extends AnyVal {

    /** Convert a Seq of RLPEncodable containing TransactionType01/02 information into a Seq of
      * Prefixed RLPEncodable.
      *
      * PrefixedRLPEncodable(prefix, prefixedRLPEncodable) generates binary data
      * as prefix || RLPEncodable(prefixedRLPEncodable).
      *
      * As prefix is a byte value lower than 0x7f, it is read back as RLPValue(prefix),
      * thus PrefixedRLPEncodable is binary equivalent to RLPValue(prefix), RLPEncodable
      *
      * The method aggregates back the typed transaction prefix with the following heuristic:
      * - a RLPValue(byte) with byte < 07f + the following RLPEncodable are associated as a PrefixedRLPEncodable
      * - all other RLPEncodable are kept unchanged
      *
      * This is the responsibility of the RLPDecoder to insert this meaning into its RLPList, when appropriate.
      *
      * @return a Seq of TransactionTypeX enriched RLPEncodable
      */
    @nowarn("msg=match may not be exhaustive") // the compiler can not check exhaustiveness for List extractors
    def toTypedRLPEncodables: Seq[RLPEncodeable] =
      encodables match {
        case Seq() => Seq()
        case Seq(RLPValue(v), rlpList: RLPList, tail @ _*) if v.isValidTransactionType =>
          PrefixedRLPEncodable(v.head, rlpList) +: tail.toTypedRLPEncodables
        case Seq(head, tail @ _*) => head +: tail.toTypedRLPEncodables
      }
  }
}

object LegacyTransaction {
  def apply(
      nonce: Nonce,
      gasPrice: BigInt,
      gasLimit: BigInt,
      receivingAddress: Address,
      value: BigInt,
      payload: ByteString
  ): LegacyTransaction =
    LegacyTransaction(nonce, gasPrice, gasLimit, Some(receivingAddress), value, payload)
}

final case class LegacyTransaction(
    nonce: Nonce,
    gasPrice: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString
) extends Transaction {
  override def maxPriorityFeePerGas: BigInt = gasPrice
  override def maxFeePerGas: BigInt         = gasPrice
  override def toString: String =
    s"LegacyTransaction {" +
      s"nonce: ${nonce.value} " +
      s"gasPrice: $gasPrice " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"}"
}

object TransactionType01 {
  def apply(
      chainId: BigInt,
      nonce: Nonce,
      gasPrice: BigInt,
      gasLimit: BigInt,
      receivingAddress: Address,
      value: BigInt,
      payload: ByteString,
      accessList: List[AccessListItem]
  ): TransactionType01 =
    TransactionType01(chainId, nonce, gasPrice, gasLimit, Some(receivingAddress), value, payload, accessList)
}

final case class TransactionType01(
    chainId: BigInt,
    nonce: Nonce,
    gasPrice: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString,
    accessList: List[AccessListItem]
) extends TypedTransaction {
  override def maxPriorityFeePerGas: BigInt = gasPrice
  override def maxFeePerGas: BigInt         = gasPrice
  override def toString: String =
    s"TransactionType01 {" +
      s"chainId: $chainId " +
      s"nonce: ${nonce.value} " +
      s"gasPrice: $gasPrice " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"accessList: $accessList" +
      s"}"

  override def getTransactionType: Byte = TypedTransaction.Type01
}

// scalastyle:off parameter.number
object TransactionType02 {
  def apply(
      chainId: BigInt,
      nonce: Nonce,
      maxPriorityFeePerGas: BigInt,
      maxFeePerGas: BigInt,
      gasLimit: BigInt,
      receivingAddress: Address,
      value: BigInt,
      payload: ByteString,
      accessList: List[AccessListItem]
  ): TransactionType02 =
    TransactionType02(
      chainId,
      nonce,
      maxPriorityFeePerGas,
      maxFeePerGas,
      gasLimit,
      Some(receivingAddress),
      value,
      payload,
      accessList
    )
}

final case class TransactionType02(
    chainId: BigInt,
    nonce: Nonce,
    maxPriorityFeePerGas: BigInt,
    maxFeePerGas: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString,
    accessList: List[AccessListItem]
) extends TypedTransaction {

  override def toString: String =
    s"TransactionType02 {" +
      s"chainId: $chainId " +
      s"nonce: ${nonce.value} " +
      s"maxPriorityFeePerGas: $maxPriorityFeePerGas " +
      s"maxFeePerGas: $maxFeePerGas " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"accessList: $accessList" +
      s"}"

  override def getTransactionType: Byte = TypedTransaction.Type02
}

final case class AccessListItem(address: Address, storageKeys: List[BigInt]) // bytes32

object AccessListItem {
  import io.iohk.ethereum.rlp.RLPImplicitConversions._
  import io.iohk.ethereum.rlp.RLPImplicits.bigIntEncDec
  import io.iohk.ethereum.rlp.RLPImplicits.byteArrayEncDec
  import io.iohk.ethereum.rlp.RLPCodec.Ops

  implicit val addressCodec: RLPCodec[Address] =
    implicitly[RLPCodec[Array[Byte]]].xmap(Address(_), _.toArray)

  implicit val accessListItemCodec: RLPCodec[AccessListItem] =
    RLPCodec.instance[AccessListItem](
      { case AccessListItem(address, storageKeys) =>
        RLPList(address, toRlpList(storageKeys.map(UInt256(_).bytes.toArray)))
      },
      {
        case r: RLPList if r.items.isEmpty => AccessListItem(Address(ByteString.empty), List.empty)

        case RLPList(rlpAddress, rlpStorageKeys: RLPList) =>
          val address     = rlpAddress.decodeAs[Address]("address")
          val storageKeys = fromRlpList[BigInt](rlpStorageKeys).toList
          AccessListItem(address, storageKeys)
      }
    )
}
