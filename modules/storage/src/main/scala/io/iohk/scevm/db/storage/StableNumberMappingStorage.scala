package io.iohk.scevm.db.storage

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.db.dataSource.{DataSource, DataSourceBatchUpdate}
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock}
import io.iohk.scevm.storage.metrics.StorageMetrics

import java.math.BigInteger
import scala.collection.immutable.ArraySeq

trait StableNumberMappingStorage[F[_]] {
  def getBlockHash(number: BlockNumber): F[Option[BlockHash]]
  def insertBlockHash(number: BlockNumber, hash: BlockHash): F[Unit]
  def insertBlockHashWithoutCommit(number: BlockNumber, hash: BlockHash): DataSourceBatchUpdate
  def remove(number: BlockNumber): DataSourceBatchUpdate
}

/** Stores a one-to-one mapping between a block number and the corresponding hash
  *   - Key: the number of the stabilized block
  *   - Value: the hash of the block
  */
class StableNumberMappingStorageImpl[F[_]: Sync](val dataSource: DataSource)
    extends StableNumberMappingStorage[F]
    with TransactionalKeyValueStorage[BlockNumber, BlockHash] {

  override val namespace: IndexedSeq[Byte] = Namespaces.StableBlockNumberMapping

  override def keySerializer: BlockNumber => IndexedSeq[Byte] =
    index => ArraySeq.unsafeWrapArray(index.value.toByteArray)

  override def keyDeserializer: IndexedSeq[Byte] => BlockNumber =
    bytes => BlockNumber(new BigInteger(bytes.toArray))

  override def valueSerializer: BlockHash => IndexedSeq[Byte] =
    hashedBlockHeaderSerializer

  override def valueDeserializer: IndexedSeq[Byte] => BlockHash =
    hashedBlockHeaderDeserializer

  override def getBlockHash(number: BlockNumber): F[Option[BlockHash]] = Sync[F].delay(get(number))

  override def insertBlockHash(number: BlockNumber, hash: BlockHash): F[Unit] =
    Sync[F].delay(put(number, hash).commit())

  override def insertBlockHashWithoutCommit(number: BlockNumber, hash: BlockHash): DataSourceBatchUpdate =
    put(number, hash)
}

object StableNumberMappingStorage {

  def init[F[_]: Sync](stableNumberMappingStorage: StableNumberMappingStorage[F])(genesis: ObftBlock): F[Unit] =
    for {
      genesisFromStableNumberMapping <- stableNumberMappingStorage.getBlockHash(genesis.number)
      _ <- stableNumberMappingStorage
             .insertBlockHash(genesis.number, genesis.hash)
             .whenA(genesisFromStableNumberMapping.isEmpty)
    } yield ()
}

class MonitoredStableNumberMappingStorage[F[_]](
    monitored: StableNumberMappingStorage[F],
    storageMetrics: StorageMetrics[F]
) extends StableNumberMappingStorage[F] {
  override def getBlockHash(number: BlockNumber): F[Option[BlockHash]] =
    storageMetrics.readTimeForStableNumberMapping.observe(monitored.getBlockHash(number))

  override def insertBlockHash(number: BlockNumber, hash: BlockHash): F[Unit] =
    storageMetrics.writeTimeForStableNumberMapping.observe(monitored.insertBlockHash(number, hash))

  override def insertBlockHashWithoutCommit(number: BlockNumber, hash: BlockHash): DataSourceBatchUpdate =
    monitored.insertBlockHashWithoutCommit(number, hash)

  override def remove(number: BlockNumber): DataSourceBatchUpdate = monitored.remove(number)
}
