package io.iohk.scevm.domain

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.TransactionOutcome._
import io.iohk.scevm.serialization.ByteArraySerializable
import org.bouncycastle.util.encoders.Hex

/** @param postTransactionStateHash For blocks where block.number >= byzantium-block-number (from config),
  *                                 the intermediate state root is replaced by a status code,
  *                                 0 indicating failure [[FailureOutcome]] (due to any operation that can cause
  *                                 the transaction or top-level call to revert)
  *                                 1 indicating success [[SuccessOutcome]].
  *                                 For other blocks state root stays [[HashOutcome]].
  *
  * More description: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-658.md
  */
final case class ReceiptBody(
    postTransactionStateHash: TransactionOutcome,
    cumulativeGasUsed: BigInt,
    logsBloomFilter: ByteString,
    logs: Seq[TransactionLogEntry]
)

sealed abstract class Receipt(body: ReceiptBody) {
  def postTransactionStateHash: TransactionOutcome = body.postTransactionStateHash
  def cumulativeGasUsed: BigInt                    = body.cumulativeGasUsed
  def logsBloomFilter: ByteString                  = body.logsBloomFilter
  def logs: Seq[TransactionLogEntry]               = body.logs

  override def toString: String = {
    val className = this match {
      case _: LegacyReceipt => "Legacy"
      case _: Type01Receipt => "Type01Receipt"
      case _: Type02Receipt => "Type02Receipt"
    }

    val stateHash = body.postTransactionStateHash match {
      case HashOutcome(hash) => hash.toArray[Byte]
      case SuccessOutcome    => Array(1.toByte)
      case FailureOutcome    => Array(0.toByte)
    }

    s"${className}{ " +
      s"postTransactionStateHash: ${Hex.toHexString(stateHash)}, " +
      s"cumulativeGasUsed: ${body.cumulativeGasUsed}, " +
      s"logsBloomFilter: ${Hex.toHexString(body.logsBloomFilter.toArray[Byte])}, " +
      s"logs: ${body.logs}" +
      s"}"
  }
}

final case class LegacyReceipt(receiptBody: ReceiptBody) extends Receipt(receiptBody)
final case class Type01Receipt(receiptBody: ReceiptBody) extends Receipt(receiptBody)
final case class Type02Receipt(receiptBody: ReceiptBody) extends Receipt(receiptBody)

object Receipt {
  implicit val show: Show[Receipt] = Show.fromToString

  final case class StatusCode(code: ByteString)

  val byteArraySerializable: ByteArraySerializable[Receipt] = new ByteArraySerializable[Receipt] {
    import io.iohk.scevm.domain.Receipt.ReceiptRLPImplicits.ReceiptDec
    import io.iohk.scevm.domain.Receipt.ReceiptRLPImplicits.ReceiptEnc

    override def fromBytes(bytes: Array[Byte]): Receipt = bytes.toReceipt

    override def toBytes(input: Receipt): Array[Byte] = input.toBytes
  }

  object ReceiptRLPImplicits {

    import io.iohk.ethereum.rlp._
    import io.iohk.ethereum.rlp.RLPSerializable
    import io.iohk.ethereum.rlp.RLPEncodeable
    import io.iohk.ethereum.rlp.RLPList
    import io.iohk.ethereum.rlp.RLPImplicitConversions._
    import io.iohk.ethereum.rlp.RLPImplicits._
    import io.iohk.scevm.domain.TransactionLogEntry.TransactionLogEntryRLPImplicits._
    import TransactionOutcome._

    implicit class ReceiptEnc(receipt: Receipt) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = {
        import receipt._
        val stateHash: RLPEncodeable = postTransactionStateHash match {
          case HashOutcome(hash) => hash
          case SuccessOutcome    => 1.toByte
          case _                 => 0.toByte
        }
        val rlpReceiptBody =
          RLPList(stateHash, cumulativeGasUsed, logsBloomFilter, RLPList(logs.map(_.toRLPEncodable): _*))

        receipt match {
          case _: LegacyReceipt => rlpReceiptBody
          case _: Type01Receipt => PrefixedRLPEncodable(TypedTransaction.Type01, rlpReceiptBody)
          case _: Type02Receipt => PrefixedRLPEncodable(TypedTransaction.Type02, rlpReceiptBody)
        }
      }
    }

    implicit class ReceiptSeqEnc(receipts: Seq[Receipt]) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = RLPList(receipts.map(_.toRLPEncodable): _*)
    }

    implicit class ReceiptDec(val bytes: Array[Byte]) extends AnyVal {
      def toReceipt: Receipt = {
        val first = bytes(0)
        (first match {
          case TypedTransaction.Type01 => PrefixedRLPEncodable(TypedTransaction.Type01, rawDecode(bytes.tail))
          case TypedTransaction.Type02 => PrefixedRLPEncodable(TypedTransaction.Type02, rawDecode(bytes.tail))
          case _                       => rawDecode(bytes)
        }).toReceipt
      }
    }

    implicit class ReceiptRLPEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
      def toReceiptBody: ReceiptBody = rlpEncodeable match {
        case RLPList(postTransactionStateHash, cumulativeGasUsed, logsBloomFilter, logs: RLPList) =>
          val stateHash = postTransactionStateHash match {
            case RLPValue(bytes) if bytes.length > 1                     => HashOutcome(ByteString(bytes))
            case RLPValue(bytes) if bytes.length == 1 && bytes.head == 1 => SuccessOutcome
            case _                                                       => FailureOutcome
          }
          ReceiptBody(stateHash, cumulativeGasUsed, logsBloomFilter, logs.items.map(_.toTransactionLogEntry))
        case _ => throw new RuntimeException("Cannot decode ReceiptBody")
      }

      def toReceipt: Receipt = rlpEncodeable match {
        case PrefixedRLPEncodable(TypedTransaction.Type01, legacyReceipt) => Type01Receipt(legacyReceipt.toReceiptBody)
        case PrefixedRLPEncodable(TypedTransaction.Type02, legacyReceipt) => Type02Receipt(legacyReceipt.toReceiptBody)
        case other                                                        => LegacyReceipt(other.toReceiptBody)
      }
    }

  }
}
