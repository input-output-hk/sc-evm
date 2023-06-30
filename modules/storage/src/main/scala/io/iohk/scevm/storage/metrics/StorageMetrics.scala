package io.iohk.scevm.storage.metrics

import io.iohk.scevm.metrics.instruments.Histogram
import io.prometheus.client.{Histogram => ClientHistogram}

trait StorageMetrics[F[_]] {
  val readTimeForReverseStorage: Histogram[F] // ReverseChainStorageImpl

  val readTimeForBlockHeaders: Histogram[F]  // alternativeBlockHeadersStorage
  val writeTimeForBlockHeaders: Histogram[F] // alternativeBlockHeadersStorage + reverseChainStorage

  val readTimeForBlockBodies: Histogram[F]  // AlternativeBlockBodiesStorage
  val writeTimeForBlockBodies: Histogram[F] // AlternativeBlockBodiesStorage

  val readTimeForStableNumberMapping: Histogram[F]  // StableNumberMappingStorage
  val writeTimeForStableNumberMapping: Histogram[F] // StableNumberMappingStorage (currently only for genesis)

  val readTimeForStableTransaction: Histogram[F] // StableTransactionMappingStorage

  val writeTimeForStableIndexesUpdate: Histogram[F] // StableNumberMappingStorage + StableTransactionMappingStorage

  val readTimeForLatestStableNumber: Histogram[F]  // ObftAppStorage
  val writeTimeForLatestStableNumber: Histogram[F] // ObftAppStorage

  val readTimeForReceipts: Histogram[F]  // ReceiptStorage
  val writeTimeForReceipts: Histogram[F] // ReceiptStorage

  val readTimeForEvmCode: ClientHistogram  // EvmCodeStorage
  val writeTimeForEvmCode: ClientHistogram // EvmCodeStorage

  val readTimeForNode: ClientHistogram  // NodeStorage
  val writeTimeForNode: ClientHistogram // NodeStorage

  // Necessary as we are directly using prometheus classes for EvmCodeStorage and NodeStorage
  def unregister(): F[Unit]
}
