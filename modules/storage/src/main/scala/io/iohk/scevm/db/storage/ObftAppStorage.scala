package io.iohk.scevm.db.storage

import cats._
import cats.effect._
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.storage.metrics.StorageMetrics

import scala.collection.immutable.ArraySeq

/** This class is used to store app state variables
  *   Key: see ObftAppStateStorage.Keys
  *   Value: stored string value
  */
trait ObftAppStorage[F[_]] {
  def getLatestStableBlockHash: F[Option[BlockHash]]
  def getLatestStableBlockHashOrFail: F[BlockHash]
  def putLatestStableBlockHash(hash: BlockHash): F[Unit]
}

object ObftAppStorage {
  type Key   = String
  type Value = IndexedSeq[Byte]

  object Keys {
    val LatestStableBlockHash = "LatestStableBlockHash"
  }

  def apply[F[_]](implicit ev: ObftAppStorage[F]): ObftAppStorage[F] = ev

  final case object MissingStable extends Exception("Missing stable block")

  def unsafeCreate[F[_]: Sync](_dataSource: DataSource): ObftAppStorage[F] =
    new ObftAppStorage[F] with TransactionalKeyValueStorage[Key, Value] {
      val dataSource: DataSource      = _dataSource
      val namespace: IndexedSeq[Byte] = Namespaces.ObftAppStateNamespace

      def keySerializer: Key => IndexedSeq[Byte] =
        key => ArraySeq.unsafeWrapArray(key.getBytes(StorageStringCharset.UTF8Charset))

      def keyDeserializer: IndexedSeq[Byte] => Key =
        valueBytes => new String(valueBytes.toArray, StorageStringCharset.UTF8Charset)

      def valueSerializer: Value => IndexedSeq[Byte] =
        identity

      def valueDeserializer: IndexedSeq[Byte] => Value =
        identity

      def getLatestStableBlockHash: F[Option[BlockHash]] =
        Sync[F].delay(get(Keys.LatestStableBlockHash).map(hashedBlockHeaderDeserializer))

      def getLatestStableBlockHashOrFail: F[BlockHash] =
        Monad[F].flatMap(getLatestStableBlockHash)(stableBlockHash =>
          Sync[F].fromOption(stableBlockHash, MissingStable)
        )

      def putLatestStableBlockHash(hash: BlockHash): F[Unit] =
        Sync[F].delay(put(Keys.LatestStableBlockHash, hashedBlockHeaderSerializer(hash)).commit())
    }
}

class MonitoredObftAppStorage[F[_]](monitored: ObftAppStorage[F], storageMetrics: StorageMetrics[F])
    extends ObftAppStorage[F] {
  override def getLatestStableBlockHash: F[Option[BlockHash]] =
    storageMetrics.readTimeForLatestStableNumber.observe(monitored.getLatestStableBlockHash)

  override def getLatestStableBlockHashOrFail: F[BlockHash] =
    storageMetrics.readTimeForLatestStableNumber.observe(monitored.getLatestStableBlockHashOrFail)

  override def putLatestStableBlockHash(hash: BlockHash): F[Unit] =
    storageMetrics.writeTimeForLatestStableNumber.observe(monitored.putLatestStableBlockHash(hash))
}
