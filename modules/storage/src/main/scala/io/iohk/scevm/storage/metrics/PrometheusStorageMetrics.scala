package io.iohk.scevm.storage.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusHistogram
import io.prometheus.client.{Histogram => ClientHistogram}

final case class PrometheusStorageMetrics[F[_]: Sync] private (
    readTimeForReverseStorage: PrometheusHistogram[F],
    readTimeForBlockHeaders: PrometheusHistogram[F],
    writeTimeForBlockHeaders: PrometheusHistogram[F],
    readTimeForBlockBodies: PrometheusHistogram[F],
    writeTimeForBlockBodies: PrometheusHistogram[F],
    readTimeForStableNumberMapping: PrometheusHistogram[F],
    writeTimeForStableNumberMapping: PrometheusHistogram[F],
    readTimeForStableTransaction: PrometheusHistogram[F],
    writeTimeForStableIndexesUpdate: PrometheusHistogram[F],
    readTimeForLatestStableNumber: PrometheusHistogram[F],
    writeTimeForLatestStableNumber: PrometheusHistogram[F],
    readTimeForReceipts: PrometheusHistogram[F],
    writeTimeForReceipts: PrometheusHistogram[F],
    readTimeForEvmCode: ClientHistogram,
    writeTimeForEvmCode: ClientHistogram,
    readTimeForNode: ClientHistogram,
    writeTimeForNode: ClientHistogram
) extends StorageMetrics[F] {
  def unregister(): F[Unit] =
    Sync[F].delay {
      io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(readTimeForEvmCode)
      io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(writeTimeForEvmCode)
      io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(readTimeForNode)
      io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(writeTimeForNode)
    }
}

object PrometheusStorageMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "storage"

  // scalastyle:off method.length
  def apply[F[_]: Sync]: Resource[F, PrometheusStorageMetrics[F]] =
    for {
      // ReverseChainStorageImpl
      readTimeForReverseStorage <- PrometheusHistogram(
                                     namespace,
                                     subsystem,
                                     "read_time_reverse_storage",
                                     "Duration of reading ReverseStorage",
                                     withExemplars = true,
                                     None,
                                     None
                                   )

      // alternativeBlockHeadersStorage
      readTimeForBlockHeaders <- PrometheusHistogram(
                                   namespace,
                                   subsystem,
                                   "read_time_block_headers",
                                   "Duration of reading a block header from storage",
                                   withExemplars = true,
                                   None,
                                   None
                                 )

      // alternativeBlockHeadersStorage + reverseChainStorage
      writeTimeForBlockHeaders <- PrometheusHistogram(
                                    namespace,
                                    subsystem,
                                    "write_time_block_headers",
                                    "Duration of writing a block header into storage",
                                    withExemplars = true,
                                    None,
                                    None
                                  )

      // AlternativeBlockBodiesStorage
      readTimeForBlockBodies <- PrometheusHistogram(
                                  namespace,
                                  subsystem,
                                  "read_time_block_bodies",
                                  "Duration of reading a block body from storage",
                                  withExemplars = true,
                                  None,
                                  None
                                )
      writeTimeForBlockBodies <- PrometheusHistogram(
                                   namespace,
                                   subsystem,
                                   "write_time_block_bodies",
                                   "Duration of writing a block body into storage",
                                   withExemplars = true,
                                   None,
                                   None
                                 )

      // StableNumberMappingStorage
      readTimeForStableNumberMapping <- PrometheusHistogram(
                                          namespace,
                                          subsystem,
                                          "read_time_stable_number_mapping",
                                          "Duration of reading StableNumberMappingStorage",
                                          withExemplars = true,
                                          None,
                                          None
                                        )
      writeTimeForStableNumberMapping <- PrometheusHistogram(
                                           namespace,
                                           subsystem,
                                           "write_time_stable_number_mapping",
                                           "Duration of writing into StableNumberMappingStorage",
                                           withExemplars = true,
                                           None,
                                           None
                                         )

      // StableTransactionMappingStorage
      readTimeForStableTransaction <- PrometheusHistogram(
                                        namespace,
                                        subsystem,
                                        "read_time_stable_transaction",
                                        "Duration of reading a stable transaction",
                                        withExemplars = true,
                                        None,
                                        None
                                      )

      // StableNumberMappingStorage + StableTransactionMappingStorage
      writeTimeForStableIndexesUpdate <- PrometheusHistogram(
                                           namespace,
                                           subsystem,
                                           "write_time_stable_indexes_updated",
                                           "Duration of writing into StableNumberMappingStorage + StableTransactionMappingStorage",
                                           withExemplars = true,
                                           None,
                                           None
                                         )

      // ObftAppStorage
      readTimeForLatestStableNumber <- PrometheusHistogram(
                                         namespace,
                                         subsystem,
                                         "read_time_latest_stable_number",
                                         "Duration of reading the latest stable number",
                                         withExemplars = true,
                                         None,
                                         None
                                       )
      writeTimeForLatestStableNumber <- PrometheusHistogram(
                                          namespace,
                                          subsystem,
                                          "write_time_latest_stable_number",
                                          "Duration of writing the latest stable number into storage",
                                          withExemplars = true,
                                          None,
                                          None
                                        )

      // ReceiptStorage
      readTimeForReceipts <- PrometheusHistogram(
                               namespace,
                               subsystem,
                               "read_time_receipts",
                               "Duration of reading receipts",
                               withExemplars = true,
                               None,
                               None
                             )
      writeTimeForReceipts <- PrometheusHistogram(
                                namespace,
                                subsystem,
                                "write_time_receipts",
                                "Duration of writing receipts into storage",
                                withExemplars = true,
                                None,
                                None
                              )

      // EvmCodeStorage
      readTimeForEvmCode = PrometheusHistogram.createUnwrappedHistogram(
                             namespace,
                             subsystem,
                             "read_time_evm_codes",
                             "Duration of reading evm codes",
                             withExemplars = true,
                             None,
                             None
                           )
      writeTimeForEvmCode = PrometheusHistogram.createUnwrappedHistogram(
                              namespace,
                              subsystem,
                              "write_time_evm_codes",
                              "Duration of writing evm codes into storage",
                              withExemplars = true,
                              None,
                              None
                            )

      // NodeStorage
      readTimeForNode = PrometheusHistogram.createUnwrappedHistogram(
                          namespace,
                          subsystem,
                          "read_time_nodes",
                          "Duration of reading nodes",
                          withExemplars = true,
                          None,
                          None
                        )
      writeTimeForNode = PrometheusHistogram.createUnwrappedHistogram(
                           namespace,
                           subsystem,
                           "write_time_nodes",
                           "Duration of writing nodes into storage",
                           withExemplars = true,
                           None,
                           None
                         )
      result <- Resource.make(
                  Sync[F].delay(
                    new PrometheusStorageMetrics(
                      readTimeForReverseStorage,
                      readTimeForBlockHeaders,
                      writeTimeForBlockHeaders,
                      readTimeForBlockBodies,
                      writeTimeForBlockBodies,
                      readTimeForStableNumberMapping,
                      writeTimeForStableNumberMapping,
                      readTimeForStableTransaction,
                      writeTimeForStableIndexesUpdate,
                      readTimeForLatestStableNumber,
                      writeTimeForLatestStableNumber,
                      readTimeForReceipts,
                      writeTimeForReceipts,
                      readTimeForEvmCode,
                      writeTimeForEvmCode,
                      readTimeForNode,
                      writeTimeForNode
                    )
                  )
                )(_.unregister())
    } yield result
  // scalastyle:on method.length
}
