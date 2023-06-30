package io.iohk.scevm.db.storage

import boopickle.Default._
import cats.effect.Sync
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteUtils.{byteSequenceToBuffer, compactPickledBytes}
import io.iohk.scevm.db.dataSource.{DataSource, DataSourceBatchUpdate}
import io.iohk.scevm.domain.{TransactionHash, TransactionLocation}
import io.iohk.scevm.storage.metrics.StorageMetrics

trait StableTransactionMappingStorage[F[_]] {
  def getTransactionLocation(transactionHash: TransactionHash): F[Option[TransactionLocation]]
  def put(transactionHash: TransactionHash, transactionLocation: TransactionLocation): DataSourceBatchUpdate
}

class StableTransactionMappingStorageImpl[F[_]: Sync](val dataSource: DataSource)
    extends StableTransactionMappingStorage[F]
    with TransactionalKeyValueStorage[TransactionHash, TransactionLocation] {

  val namespace: IndexedSeq[Byte]                        = Namespaces.TransactionMappingNamespace
  def keySerializer: TransactionHash => IndexedSeq[Byte] = _.byteString.toIndexedSeq
  def keyDeserializer: IndexedSeq[Byte] => TransactionHash = bytes =>
    TransactionHash(ByteString.fromArrayUnsafe(bytes.toArray))
  def valueSerializer: TransactionLocation => IndexedSeq[Byte] = tl => compactPickledBytes(Pickle.intoBytes(tl))
  def valueDeserializer: IndexedSeq[Byte] => TransactionLocation =
    (byteSequenceToBuffer _).andThen(Unpickle[TransactionLocation].fromBytes)

  implicit val byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])

  override def getTransactionLocation(transactionHash: TransactionHash): F[Option[TransactionLocation]] =
    Sync[F].delay(get(transactionHash))
}

class MonitoredStableTransactionMappingStorage[F[_]](
    monitored: StableTransactionMappingStorage[F],
    storageMetrics: StorageMetrics[F]
) extends StableTransactionMappingStorage[F] {
  override def getTransactionLocation(transactionHash: TransactionHash): F[Option[TransactionLocation]] =
    storageMetrics.readTimeForStableTransaction.observe(monitored.getTransactionLocation(transactionHash))

  override def put(transactionHash: TransactionHash, transactionLocation: TransactionLocation): DataSourceBatchUpdate =
    monitored.put(transactionHash, transactionLocation)
}
