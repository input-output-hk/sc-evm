package io.iohk.scevm.db.storage

import cats.effect.kernel.Sync
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.domain.{BlockHash, ObftBody}
import io.iohk.scevm.storage.metrics.StorageMetrics

private trait AlternativeBlockBodiesStorage[F[_]] {
  def getBlockBody(hash: BlockHash): F[Option[ObftBody]]
  def insertBlockBody(hash: BlockHash, body: ObftBody): F[Unit]
}

/** Stores all the alternative block bodies
  *
  *   - Key: hash of the block header to which the body belongs to
  *   - Value: the block body
  *
  * The latest stable block body must be stored too (because it is the parent of the unstable chains)
  */
private class AlternativeBlockBodiesStorageImpl[F[_]: Sync](val dataSource: DataSource)
    extends AlternativeBlockBodiesStorage[F]
    with TransactionalKeyValueStorage[BlockHash, ObftBody] {

  override val namespace: IndexedSeq[Byte] = Namespaces.BodyNamespace

  override def keySerializer: BlockHash => IndexedSeq[Byte] =
    hashedBlockHeaderSerializer

  override def keyDeserializer: IndexedSeq[Byte] => BlockHash =
    hashedBlockHeaderDeserializer

  override def valueSerializer: ObftBody => IndexedSeq[Byte] =
    obftBlockBodySerializer

  override def valueDeserializer: IndexedSeq[Byte] => ObftBody =
    obftBlockBodyDeserializer

  override def getBlockBody(hash: BlockHash): F[Option[ObftBody]] = Sync[F].delay(get(hash))

  override def insertBlockBody(hash: BlockHash, body: ObftBody): F[Unit] = Sync[F].delay(put(hash, body).commit())
}

private class MonitoredAlternativeBlockBodiesStorage[F[_]](
    monitored: AlternativeBlockBodiesStorage[F],
    storageMetrics: StorageMetrics[F]
) extends AlternativeBlockBodiesStorage[F] {
  override def getBlockBody(hash: BlockHash): F[Option[ObftBody]] =
    storageMetrics.readTimeForBlockBodies.observe(monitored.getBlockBody(hash))

  override def insertBlockBody(hash: BlockHash, body: ObftBody): F[Unit] =
    storageMetrics.writeTimeForBlockBodies.observe(monitored.insertBlockBody(hash, body))
}
