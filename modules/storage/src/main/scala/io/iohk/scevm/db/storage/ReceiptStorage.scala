package io.iohk.scevm.db.storage

import boopickle.Default.{Pickle, Unpickle}
import boopickle.DefaultBasic._
import cats.effect.Sync
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.domain.TransactionOutcome.{FailureOutcome, HashOutcome, SuccessOutcome}
import io.iohk.scevm.domain.{
  Address,
  BlockHash,
  LegacyReceipt,
  Receipt,
  ReceiptBody,
  TransactionLogEntry,
  TransactionOutcome,
  Type01Receipt,
  Type02Receipt
}
import io.iohk.scevm.storage.metrics.StorageMetrics

trait ReceiptStorage[F[_]] {
  def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]]
  def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): F[Unit]
}

object ReceiptStorage {

  implicit val byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])
  implicit val hashOutcomePickler: Pickler[HashOutcome] = transformPickler[HashOutcome, ByteString] { hash =>
    HashOutcome(hash)
  }(outcome => outcome.stateHash)
  implicit val successOutcomePickler: Pickler[SuccessOutcome.type] = transformPickler[SuccessOutcome.type, ByteString] {
    _ => SuccessOutcome
  }(_ => ByteString(Array(1.toByte)))
  implicit val failureOutcomePickler: Pickler[FailureOutcome.type] = transformPickler[FailureOutcome.type, ByteString] {
    _ => FailureOutcome
  }(_ => ByteString(Array(0.toByte)))
  implicit val transactionOutcomePickler: Pickler[TransactionOutcome] = compositePickler[TransactionOutcome]
    .addConcreteType[HashOutcome]
    .addConcreteType[SuccessOutcome.type]
    .addConcreteType[FailureOutcome.type]

  implicit val addressPickler: Pickler[Address] =
    transformPickler[Address, ByteString](bytes => Address(bytes))(address => address.bytes)
  implicit val transactionLogEntryPickler: Pickler[TransactionLogEntry] =
    transformPickler[TransactionLogEntry, (Address, Seq[ByteString], ByteString)] { case (address, topics, data) =>
      TransactionLogEntry(address, topics, data)
    }(entry => (entry.loggerAddress, entry.logTopics, entry.data))

  implicit val legacyReceiptPickler: Pickler[LegacyReceipt] =
    transformPickler[LegacyReceipt, (TransactionOutcome, BigInt, ByteString, Seq[TransactionLogEntry])] {
      case (state, gas, filter, logs) => LegacyReceipt(ReceiptBody(state, gas, filter, logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter, receipt.logs)
    }

  implicit val type01ReceiptPickler: Pickler[Type01Receipt] =
    transformPickler[Type01Receipt, (TransactionOutcome, BigInt, ByteString, Seq[TransactionLogEntry])] {
      case (state, gas, filter, logs) => Type01Receipt(ReceiptBody(state, gas, filter, logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter, receipt.logs)
    }

  implicit val type02ReceiptPickler: Pickler[Type02Receipt] =
    transformPickler[Type02Receipt, (TransactionOutcome, BigInt, ByteString, Seq[TransactionLogEntry])] {
      case (state, gas, filter, logs) => Type02Receipt(ReceiptBody(state, gas, filter, logs))
    } { receipt =>
      (receipt.postTransactionStateHash, receipt.cumulativeGasUsed, receipt.logsBloomFilter, receipt.logs)
    }

  implicit val receiptPickler: Pickler[Receipt] = compositePickler[Receipt]
    .addConcreteType[LegacyReceipt]
    .addConcreteType[Type01Receipt]
    .addConcreteType[Type02Receipt]

  def unsafeCreate[F[_]: Sync](dataSource: DataSource): ReceiptStorage[F] = new ReceiptStorageImpl[F](dataSource)
}

/** This class is used to store the Receipts, by using:
  * Key: hash of the block to which the list of receipts belong
  * Value: the list of receipts
  */
private class ReceiptStorageImpl[F[_]: Sync](val dataSource: DataSource)
    extends ReceiptStorage[F]
    with TransactionalKeyValueStorage[BlockHash, Seq[Receipt]] {

  import ReceiptStorage._

  override val namespace: IndexedSeq[Byte] = Namespaces.ReceiptsNamespace

  override def keySerializer: BlockHash => IndexedSeq[Byte] = _.byteString.toIndexedSeq

  override def keyDeserializer: IndexedSeq[Byte] => BlockHash = k => BlockHash(ByteString.fromArrayUnsafe(k.toArray))

  override def valueSerializer: Seq[Receipt] => IndexedSeq[Byte] = receipts =>
    ByteUtils.compactPickledBytes(Pickle.intoBytes(receipts))

  override def valueDeserializer: IndexedSeq[Byte] => Seq[Receipt] =
    (ByteUtils.byteSequenceToBuffer _).andThen(Unpickle[Seq[Receipt]].fromBytes)

  def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]] = Sync[F].delay(get(hash))

  def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): F[Unit] = Sync[F].delay(put(hash, receipts).commit())
}

private class MonitoredReceiptStorage[F[_]](monitored: ReceiptStorage[F], storageMetrics: StorageMetrics[F])
    extends ReceiptStorage[F] {
  override def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]] =
    storageMetrics.readTimeForReceipts.observe(monitored.getReceiptsByHash(hash))

  override def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): F[Unit] =
    storageMetrics.writeTimeForReceipts.observe(monitored.putReceipts(hash, receipts))
}
