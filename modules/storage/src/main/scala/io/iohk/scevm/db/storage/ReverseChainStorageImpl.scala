package io.iohk.scevm.db.storage

import boopickle.Default.{Pickle, _}
import cats.FlatMap
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.ethereum.utils.ByteUtils.{byteSequenceToBuffer, compactPickledBytes}
import io.iohk.scevm.db.dataSource.{DataSource, DataSourceBatchUpdate}
import io.iohk.scevm.domain.{BlockHash, ObftHeader}
import io.iohk.scevm.storage.metrics.StorageMetrics
import io.iohk.scevm.utils.Picklers._

private trait ReverseChainStorage[F[_]] {
  def getChildrenHeaders(key: BlockHash): F[Option[Seq[ObftHeader]]]
  def insertChildHeaderWithoutCommit(child: ObftHeader): F[DataSourceBatchUpdate]
  def getChildren(parent: BlockHash): F[Seq[ObftHeader]]
  def findSuffixesTips(parent: BlockHash): F[Seq[ObftHeader]]
}

/** Stores a mapping between an ancestor and its children
  *   - Key: the hash of the parent
  *   - Value: the header of the children
  *
  *   - Note: storing only the hashes rather than headers would not duplicate data
  */
private class ReverseChainStorageImpl[F[_]: Sync](val dataSource: DataSource)
    extends ReverseChainStorage[F]
    with TransactionalKeyValueStorage[BlockHash, Seq[ObftHeader]] {
  override val namespace: IndexedSeq[Byte] = Namespaces.AlternativeReverseMappingNamespace

  override def keySerializer: BlockHash => IndexedSeq[Byte] =
    hashedBlockHeaderSerializer

  override def keyDeserializer: IndexedSeq[Byte] => BlockHash =
    hashedBlockHeaderDeserializer

  override def valueSerializer: Seq[ObftHeader] => IndexedSeq[Byte] =
    headers => compactPickledBytes(Pickle.intoBytes(headers))

  override def valueDeserializer: IndexedSeq[Byte] => Seq[ObftHeader] =
    (byteSequenceToBuffer _).andThen(Unpickle.apply[Seq[ObftHeader]].fromBytes)

  override def getChildrenHeaders(key: BlockHash): F[Option[Seq[ObftHeader]]] = Sync[F].delay(get(key))

  override def insertChildHeaderWithoutCommit(child: ObftHeader): F[DataSourceBatchUpdate] = {
    val key = child.parentHash
    for {
      maybeHeaders <- getChildrenHeaders(key)
      children <- Sync[F].delay(maybeHeaders match {
                    case Some(children) => (children :+ child).distinct
                    case None           => List(child)
                  })
      dataSourceBatchUpdate <- Sync[F].delay(put(key, children))
    } yield dataSourceBatchUpdate
  }

  override def getChildren(parent: BlockHash): F[Seq[ObftHeader]] = getChildrenHeaders(parent).map {
    case Some(children) => children
    case None           => List.empty
  }

  override def findSuffixesTips(parent: BlockHash): F[Seq[ObftHeader]] = {
    def helper(toVisit: List[ObftHeader], leaves: List[ObftHeader]): F[List[ObftHeader]] =
      FlatMap[F].tailRecM[(List[ObftHeader], List[ObftHeader]), List[ObftHeader]]((toVisit, leaves)) {
        case (toVisit: List[ObftHeader], leaves: List[ObftHeader]) =>
          toVisit match {
            case Nil => Sync[F].delay(Either.right(leaves))
            case visiting :: xs =>
              for {
                children <- getChildren(visiting.hash)
                result <- Sync[F].delay {
                            if (children.isEmpty) {
                              Either.left((xs, leaves :+ visiting))
                            } else {
                              Either.left((xs ++ children, leaves))
                            }
                          }
              } yield result
          }
      }

    getChildren(parent).flatMap(children => helper(children.toList, List.empty).map(_.toSeq))
  }
}

private class MonitoredReverseChainStorage[F[_]](
    monitored: ReverseChainStorage[F],
    storageMetrics: StorageMetrics[F]
) extends ReverseChainStorage[F] {
  override def getChildrenHeaders(key: BlockHash): F[Option[Seq[ObftHeader]]] =
    storageMetrics.readTimeForReverseStorage.observe(monitored.getChildrenHeaders(key))

  override def insertChildHeaderWithoutCommit(child: ObftHeader): F[DataSourceBatchUpdate] =
    monitored.insertChildHeaderWithoutCommit(child)

  override def getChildren(parent: BlockHash): F[Seq[ObftHeader]] = monitored.getChildren(parent)

  override def findSuffixesTips(parent: BlockHash): F[Seq[ObftHeader]] = monitored.findSuffixesTips(parent)
}
