package io.iohk.scevm.db.storage

import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.db.storage.BranchProvider.{BranchRetrievalError, NotConnected}
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.storage.metrics.{NoOpStorageMetrics, StorageMetrics}

object BlockchainStorage {
  def apply[F[_]](implicit ev: BlocksWriter[F] with BlocksReader[F]): BlocksWriter[F] with BlocksReader[F] = ev

  def unsafeCreate[F[_]: Sync](
      dataSource: DataSource
  ): BlocksWriter[F] with BlocksReader[F] with BranchProvider[F] =
    unsafeCreate(dataSource, NoOpStorageMetrics[F]())

  def unsafeCreate[F[_]: Sync](
      dataSource: DataSource,
      storageMetrics: StorageMetrics[F]
  ): BlocksWriter[F] with BlocksReader[F] with BranchProvider[F] = {
    val alternativeBlockBodiesStorage = new MonitoredAlternativeBlockBodiesStorage[F](
      new AlternativeBlockBodiesStorageImpl[F](dataSource),
      storageMetrics
    )
    val alternativeBlockHeadersStorage = new MonitoredAlternativeBlockHeadersStorage(
      new AlternativeBlockHeadersStorageImpl[F](dataSource),
      storageMetrics
    )
    val reverseChainStorage = new MonitoredReverseChainStorage[F](
      new ReverseChainStorageImpl[F](dataSource),
      storageMetrics
    )

    new BlockchainStorageImpl[F](
      alternativeBlockBodiesStorage,
      alternativeBlockHeadersStorage,
      reverseChainStorage
    )
  }
}

/** The common implementation of different data access services.
  * Composition of queries will allow to remove this common implementation while
  * keeping the guarantees of having consistent inserts (header, body and reverse mapping).
  */
private class BlockchainStorageImpl[F[_]: Sync](
    protected val alternativeBlockBodiesStorage: AlternativeBlockBodiesStorage[F],
    protected val alternativeBlockHeadersStorage: AlternativeBlockHeadersStorage[F],
    protected val reverseChainStorage: ReverseChainStorage[F]
) extends BlocksWriter[F]
    with BlocksReader[F]
    with BranchProvider[F] {

  def getBlockHeader(hash: BlockHash): F[Option[ObftHeader]] = alternativeBlockHeadersStorage.getHeader(hash)

  def getBlockBody(hash: BlockHash): F[Option[ObftBody]] = alternativeBlockBodiesStorage.getBlockBody(hash)

  def getBlock(hash: BlockHash): F[Option[ObftBlock]] = (for {
    header <- OptionT(getBlockHeader(hash))
    body   <- OptionT(getBlockBody(hash))
  } yield ObftBlock(header, body)).value

  def insertHeader(header: ObftHeader): F[Unit] =
    for {
      updateBlockHeaders <- Sync[F].delay(alternativeBlockHeadersStorage.put(header))
      updateReverseChain <-
        reverseChainStorage.insertChildHeaderWithoutCommit(header) // Add the block as a child of its parent in storage
      _ <- Sync[F].delay(updateBlockHeaders.and(updateReverseChain).commit())
    } yield ()

  def insertBody(blockHash: BlockHash, body: ObftBody): F[Unit] =
    alternativeBlockBodiesStorage.insertBlockBody(blockHash, body)

  def getChildren(parent: BlockHash): F[Seq[ObftHeader]] = reverseChainStorage.getChildren(parent)

  def fetchChain(from: ObftHeader, to: ObftHeader): F[Either[BranchRetrievalError, List[ObftHeader]]] = {
    def branchIsConnected(branch: List[ObftHeader]): Boolean =
      branch.headOption.contains(to)

    alternativeBlockHeadersStorage
      .getBranch(from, from.number.distance(to.number).value + 1)
      .map(_.filterOrElse(branchIsConnected, NotConnected))
  }

  /** Return the tips of children branches (leaves) */
  override def findSuffixesTips(parent: BlockHash): F[Seq[ObftHeader]] = reverseChainStorage.findSuffixesTips(parent)
}

private class MonitoredBlockchainStorage[F[_]: Sync](
    monitored: BlocksWriter[F] with BlocksReader[F] with BranchProvider[F],
    storageMetrics: StorageMetrics[F]
) extends BlocksWriter[F]
    with BlocksReader[F]
    with BranchProvider[F] {
  override def getBlockHeader(hash: BlockHash): F[Option[ObftHeader]] = monitored.getBlockHeader(hash)

  override def getBlockBody(hash: BlockHash): F[Option[ObftBody]] = monitored.getBlockBody(hash)

  override def getBlock(hash: BlockHash): F[Option[ObftBlock]] = monitored.getBlock(hash)

  override def insertHeader(header: ObftHeader): F[Unit] =
    storageMetrics.writeTimeForBlockHeaders.observe(monitored.insertHeader(header))

  override def insertBody(blockHash: BlockHash, body: ObftBody): F[Unit] = monitored.insertBody(blockHash, body)

  override def getChildren(parent: BlockHash): F[Seq[ObftHeader]] = monitored.getChildren(parent)

  override def fetchChain(from: ObftHeader, to: ObftHeader): F[Either[BranchRetrievalError, List[ObftHeader]]] =
    monitored.fetchChain(from, to)

  override def findSuffixesTips(parent: BlockHash): F[Seq[ObftHeader]] = monitored.findSuffixesTips(parent)
}
