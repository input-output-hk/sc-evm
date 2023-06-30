package io.iohk.scevm.db.storage

import cats.effect.{Resource, Sync}
import cats.syntax.all._
import io.iohk.scevm.db.DataSourceConfig
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.storage.metrics.StorageMetrics

import java.nio.file.Path
import scala.util.Try

final case class ObftStorageBuilder[F[_]](
    appStorage: ObftAppStorage[F],
    stableNumberMappingStorage: StableNumberMappingStorage[F],
    blockchainStorage: BlocksWriter[F] with BlocksReader[F] with BranchProvider[F],
    nodeStorage: NodeStorage,
    stateStorage: StateStorage,
    receiptStorage: ReceiptStorage[F],
    evmCodeStorage: EvmCodeStorage,
    transactionMappingStorage: StableTransactionMappingStorage[F],
    backupStorage: () => F[Try[Either[String, Path]]]
)

object ObftStorageBuilder {

  def create[F[_]: Sync](
      dsConfig: DataSourceConfig,
      storageMetrics: StorageMetrics[F]
  ): Resource[F, ObftStorageBuilder[F]] =
    for {
      dataSource <- DataSource[F](dsConfig)
      builder    <- Resource.eval(ObftStorageBuilder(dataSource, storageMetrics))
    } yield builder

  def apply[F[_]: Sync](dataSource: DataSource, storageMetrics: StorageMetrics[F]): F[ObftStorageBuilder[F]] =
    for {
      appStorage <-
        Sync[F].delay(new MonitoredObftAppStorage(ObftAppStorage.unsafeCreate[F](dataSource), storageMetrics))
      stableNumberMappingStorage = new MonitoredStableNumberMappingStorage[F](
                                     new StableNumberMappingStorageImpl[F](dataSource),
                                     storageMetrics
                                   )
      blockchainStorage = new MonitoredBlockchainStorage[F](
                            BlockchainStorage.unsafeCreate[F](dataSource, storageMetrics),
                            storageMetrics
                          )
      nodeStorage    = new NodeStorage(dataSource, storageMetrics.readTimeForNode, storageMetrics.writeTimeForNode)
      stateStorage   = StateStorage(nodeStorage)
      receiptStorage = new MonitoredReceiptStorage(ReceiptStorage.unsafeCreate[F](dataSource), storageMetrics)
      evmCodeStorage =
        new EvmCodeStorageImpl(dataSource, storageMetrics.readTimeForEvmCode, storageMetrics.writeTimeForEvmCode)
      transactionMappingStorage = new MonitoredStableTransactionMappingStorage[F](
                                    new StableTransactionMappingStorageImpl[F](dataSource),
                                    storageMetrics
                                  )
    } yield ObftStorageBuilder[F](
      appStorage,
      stableNumberMappingStorage,
      blockchainStorage,
      nodeStorage,
      stateStorage,
      receiptStorage,
      evmCodeStorage,
      transactionMappingStorage,
      () => Sync[F].delay(dataSource.backup())
    )
}
