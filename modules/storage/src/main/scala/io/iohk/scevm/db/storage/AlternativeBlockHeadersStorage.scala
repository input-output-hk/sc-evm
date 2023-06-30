package io.iohk.scevm.db.storage

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.db.dataSource.{DataSource, DataSourceBatchUpdate}
import io.iohk.scevm.db.storage.BranchProvider.MissingParent
import io.iohk.scevm.domain.{BlockHash, ObftHeader}
import io.iohk.scevm.storage.metrics.StorageMetrics

private[storage] trait AlternativeBlockHeadersStorage[F[_]] {
  def getHeader(hash: BlockHash): F[Option[ObftHeader]]
  def getBranch(from: ObftHeader, length: BigInt): F[Either[MissingParent, List[ObftHeader]]]
  def put(header: ObftHeader): DataSourceBatchUpdate
}

/** Stores all the alternatives block headers
  *
  *   - Key: hash of the block header to which the body belongs to
  *   - Value: the block header
  *
  * The latest stable block header must be stored too (because it is the parent of the unstable chains)
  */
private[storage] class AlternativeBlockHeadersStorageImpl[F[_]: Sync](val dataSource: DataSource)
    extends AlternativeBlockHeadersStorage[F]
    with TransactionalKeyValueStorage[BlockHash, ObftHeader] {

  override val namespace: IndexedSeq[Byte] = Namespaces.AlternativeBlockHeadersNamespace

  override val keySerializer: BlockHash => IndexedSeq[Byte] =
    AlternativeBlockHeadersStorage.keySerializer

  override val keyDeserializer: IndexedSeq[Byte] => BlockHash =
    AlternativeBlockHeadersStorage.keyDeserializer

  override val valueSerializer: ObftHeader => IndexedSeq[Byte] =
    AlternativeBlockHeadersStorage.valueSerializer

  override val valueDeserializer: IndexedSeq[Byte] => ObftHeader =
    AlternativeBlockHeadersStorage.valueDeserializer

  override def getHeader(hash: BlockHash): F[Option[ObftHeader]] = Sync[F].delay(get(hash))

  /** Retrieve the headers forming a branch
    *
    * @param from the tip of the branch
    * @param length limits the search to the expected branch length
    * @return returns the headers in the chronological order, or the missing parent
    */
  override def getBranch(from: ObftHeader, length: BigInt): F[Either[MissingParent, List[ObftHeader]]] =
    if (length < 1) Sync[F].delay(Right(List.empty))
    else {
      (from.hash, from.number, List.empty[ObftHeader], length, from).tailRecM {
        case (hash: BlockHash, number, acc: List[ObftHeader], limit: BigInt, ancestor: ObftHeader) =>
          getHeader(hash).map {
            case Some(header) =>
              if (limit <= 1) Either.right(Right(header +: acc))
              else Either.left((header.parentHash, number.previous, header +: acc, limit - 1, header))
            case None =>
              Either.right(Left(MissingParent(hash, number, ancestor)))
          }
      }
    }

  def put(header: ObftHeader): DataSourceBatchUpdate = put(header.hash, header)

}

object AlternativeBlockHeadersStorage {
  val keySerializer: BlockHash => IndexedSeq[Byte]      = hashedBlockHeaderSerializer
  val keyDeserializer: IndexedSeq[Byte] => BlockHash    = hashedBlockHeaderDeserializer
  val valueSerializer: ObftHeader => IndexedSeq[Byte]   = obftBlockHeaderSerializer
  val valueDeserializer: IndexedSeq[Byte] => ObftHeader = obftBlockHeaderDeserializer
}

private class MonitoredAlternativeBlockHeadersStorage[F[_]](
    monitored: AlternativeBlockHeadersStorage[F],
    storageMetrics: StorageMetrics[F]
) extends AlternativeBlockHeadersStorage[F] {
  override def getHeader(hash: BlockHash): F[Option[ObftHeader]] =
    storageMetrics.readTimeForBlockHeaders.observe(monitored.getHeader(hash))

  override def getBranch(from: ObftHeader, length: BigInt): F[Either[MissingParent, List[ObftHeader]]] =
    monitored.getBranch(from, length)

  override def put(header: ObftHeader): DataSourceBatchUpdate = monitored.put(header)
}
