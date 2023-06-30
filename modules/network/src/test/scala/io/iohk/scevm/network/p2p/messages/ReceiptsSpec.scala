package io.iohk.scevm.network.p2p.messages

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.TransactionOutcome.HashOutcome
import io.iohk.scevm.domain.{
  Address,
  LegacyReceipt,
  Receipt,
  ReceiptBody,
  TransactionLogEntry,
  Type01Receipt,
  Type02Receipt,
  TypedTransaction
}
import io.iohk.scevm.network.RequestId
import io.iohk.scevm.network.p2p.messages.OBFT1.Receipts
import io.iohk.scevm.network.p2p.{Codes, EthereumMessageDecoder, ProtocolVersions}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ReceiptsSpec extends AnyWordSpec with Matchers {
  val exampleHash: ByteString      = ByteString(kec256((0 until 32).map(_ => 1: Byte).toArray))
  val exampleLogsBloom: ByteString = ByteString((0 until 256).map(_ => 1: Byte).toArray)

  val loggerAddress: Address     = Address(0xff)
  val logData: ByteString        = Hex.decodeUnsafe("bb")
  val logTopics: Seq[ByteString] = Seq(Hex.decodeUnsafe("dd"), Hex.decodeUnsafe("aa"))

  val exampleLog: TransactionLogEntry = TransactionLogEntry(loggerAddress, logTopics, logData)

  val cumulativeGas: BigInt = 0

  val receiptBody: ReceiptBody = ReceiptBody(
    postTransactionStateHash = HashOutcome(exampleHash),
    cumulativeGasUsed = cumulativeGas,
    logsBloomFilter = exampleLogsBloom,
    logs = Seq(exampleLog)
  )

  val legacyReceipt: LegacyReceipt = LegacyReceipt(receiptBody)

  val type01Receipt: Receipt = Type01Receipt(receiptBody)
  val type02Receipt: Receipt = Type02Receipt(receiptBody)

  val legacyReceipts: Receipts = Receipts(RequestId(0), Seq(Seq(legacyReceipt)))
  val type01Receipts: Receipts = Receipts(RequestId(0), Seq(Seq(type01Receipt)))
  val type02Receipts: Receipts = Receipts(RequestId(0), Seq(Seq(type02Receipt)))

  val rlpEncodedReceiptBody: RLPList = RLPList(
    exampleHash,
    cumulativeGas,
    exampleLogsBloom,
    RLPList(RLPList(loggerAddress.bytes, logTopics, logData))
  )

  val encodedLegacyReceipts: RLPList =
    RLPList(0, RLPList(RLPList(rlpEncodedReceiptBody)))

  val encodedType01Receipts: RLPList =
    RLPList(
      0,
      RLPList(
        RLPList(
          PrefixedRLPEncodable(
            TypedTransaction.Type01,
            rlpEncodedReceiptBody
          )
        )
      )
    )

  val encodedType02Receipts: RLPList =
    RLPList(
      0,
      RLPList(
        RLPList(
          PrefixedRLPEncodable(
            TypedTransaction.Type02,
            rlpEncodedReceiptBody
          )
        )
      )
    )

  "Legacy Receipts" should {
    "encode legacy receipts" in {
      (legacyReceipts.toBytes: Array[Byte]) shouldBe encode(encodedLegacyReceipts)
    }
    "decode legacy receipts" in {
      EthereumMessageDecoder
        .ethMessageDecoder(ProtocolVersions.PV1)
        .fromBytes(
          Codes.ReceiptsCode,
          encode(encodedLegacyReceipts)
        ) shouldBe Right(legacyReceipts)
    }
    "decode encoded legacy receipts" in {
      EthereumMessageDecoder
        .ethMessageDecoder(ProtocolVersions.PV1)
        .fromBytes(
          Codes.ReceiptsCode,
          legacyReceipts.toBytes
        ) shouldBe Right(legacyReceipts)
    }
  }

  "Type 01 Receipts" should {
    "encode type 01 receipts" in {
      (type01Receipts.toBytes: Array[Byte]) shouldBe encode(encodedType01Receipts)
    }
    "decode type 01 receipts" in {
      EthereumMessageDecoder
        .ethMessageDecoder(ProtocolVersions.PV1)
        .fromBytes(
          Codes.ReceiptsCode,
          encode(encodedType01Receipts)
        ) shouldBe Right(type01Receipts)
    }
    "decode encoded type 01 receipts" in {
      EthereumMessageDecoder
        .ethMessageDecoder(ProtocolVersions.PV1)
        .fromBytes(Codes.ReceiptsCode, type01Receipts.toBytes) shouldBe Right(type01Receipts)
    }
  }

  "Type 02 Receipts" should {
    "encode type 02 receipts" in {
      (type02Receipts.toBytes: Array[Byte]) shouldBe encode(encodedType02Receipts)
    }
    "decode type 02 receipts" in {
      EthereumMessageDecoder
        .ethMessageDecoder(ProtocolVersions.PV1)
        .fromBytes(
          Codes.ReceiptsCode,
          encode(encodedType02Receipts)
        ) shouldBe Right(type02Receipts)
    }
    "decode encoded type 02 receipts" in {
      EthereumMessageDecoder
        .ethMessageDecoder(ProtocolVersions.PV1)
        .fromBytes(Codes.ReceiptsCode, type02Receipts.toBytes) shouldBe Right(type02Receipts)
    }
  }
}
