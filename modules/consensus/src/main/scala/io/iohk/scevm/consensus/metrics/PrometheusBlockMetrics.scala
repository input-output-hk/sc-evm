package io.iohk.scevm.consensus.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.{PrometheusCounter, PrometheusGauge, PrometheusHistogram}
import io.iohk.scevm.metrics.instruments.{Counter, Gauge, Histogram}

final class PrometheusBlockMetrics[F[_]] private (
    durationOfBlockCreation: PrometheusHistogram[F],
    blockCreationError: Counter[F],
    blockTransactions: PrometheusGauge[F],
    gasLimit: PrometheusGauge[F],
    gasUsed: PrometheusGauge[F],
    durationOfBlockExecution: PrometheusHistogram[F],
    blockExecutionError: PrometheusCounter[F],
    timeToFinalization: PrometheusGauge[F],
    executedBlocks: PrometheusCounter[F]
) extends BlockMetrics[F] {
  override val durationOfBlockCreationHistogram: Histogram[F]  = durationOfBlockCreation
  override val blockCreationErrorCounter: Counter[F]           = blockCreationError
  override val blockTransactionsGauge: Gauge[F]                = blockTransactions
  override val gasLimitGauge: Gauge[F]                         = gasLimit
  override val gasUsedGauge: Gauge[F]                          = gasUsed
  override val durationOfBlockExecutionHistogram: Histogram[F] = durationOfBlockExecution
  override val blockExecutionErrorCounter: Counter[F]          = blockExecutionError
  override val timeToFinalizationGauge: Gauge[F]               = timeToFinalization
  override val executedBlocksCounter: Counter[F]               = executedBlocks
}

object PrometheusBlockMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "block"

  // scalastyle:off method.length
  def apply[F[_]: Sync]: Resource[F, PrometheusBlockMetrics[F]] =
    for {
      durationOfBlockCreation <- PrometheusHistogram(
                                   namespace,
                                   subsystem,
                                   "creation_time",
                                   "Time taken creating a block",
                                   withExemplars = true,
                                   None,
                                   None
                                 )
      blockCreationError <-
        PrometheusCounter(
          namespace,
          subsystem,
          "creation_errors",
          "Error occurred during block creation",
          withExemplars = true,
          None
        )
      blockTransactions <-
        PrometheusGauge(namespace, subsystem, "transactions_count", "Amount of transactions in block")
      gasLimit <- PrometheusGauge(
                    namespace,
                    subsystem,
                    "gas_limit",
                    "Maximum amount of gas to be spend by given block"
                  )
      gasUsed <- PrometheusGauge(namespace, subsystem, "gas_used", "Amount of gas used by given block")
      durationOfBlockExecution <- PrometheusHistogram(
                                    namespace,
                                    subsystem,
                                    "execution_time",
                                    "Duration of block execution",
                                    withExemplars = true,
                                    None,
                                    None
                                  )
      blockExecutionError <- PrometheusCounter(
                               namespace,
                               subsystem,
                               "execution_errors",
                               "Number of errors during block execution",
                               withExemplars = true,
                               None
                             )
      timeToFinalization <- PrometheusGauge(
                              namespace,
                              subsystem,
                              "time_to_finalization",
                              "Duration for a block to become stable after being received or generated"
                            )
      executedBlocks <-
        PrometheusCounter(
          namespace,
          subsystem,
          "executed_blocks",
          "Number of executed blocks",
          withExemplars = false,
          None
        )
    } yield new PrometheusBlockMetrics(
      durationOfBlockCreation,
      blockCreationError,
      blockTransactions,
      gasLimit,
      gasUsed,
      durationOfBlockExecution,
      blockExecutionError,
      timeToFinalization,
      executedBlocks
    )
  // scalastyle:on method.length
}
