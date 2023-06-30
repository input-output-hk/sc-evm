package io.iohk.scevm.ledger.blockgeneration

import cats.effect.Sync
import cats.implicits._
import io.iohk.scevm.consensus.metrics.BlockMetrics
import io.iohk.scevm.domain.{ObftBlock, ObftHeader, SidechainPrivateKey, SidechainPublicKey, SignedTransaction, Slot}
import io.iohk.scevm.ledger.BlockPreparation
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import io.janstenpickle.trace4cats.inject.Trace

import scala.util.{Failure, Success, Try}

class MonitoredBlockGeneratorImp[F[_]: Sync: Trace](
    blockPreparation: BlockPreparation[F],
    blockMetrics: BlockMetrics[F]
) extends BlockGeneratorImpl[F](blockPreparation) {
  override def generateBlock(
      parent: ObftHeader,
      transactionList: Seq[SignedTransaction],
      slotNumber: Slot,
      timestamp: UnixTimestamp,
      validatorPubKey: SidechainPublicKey,
      validatorPrvKey: SidechainPrivateKey
  ): F[ObftBlock] = {
    val tracedGenerateBlock = for {
      traceId <- Trace[F].traceId
      result <- {
        val tryBlockGeneration = Try(
          blockMetrics.durationOfBlockCreationHistogram.observe(
            super.generateBlock(parent, transactionList, slotNumber, timestamp, validatorPubKey, validatorPrvKey),
            Map("traceId" -> traceId.getOrElse("N/A"))
          )
        )
        tryBlockGeneration match {
          case Success(block) => block
          case Failure(ex) =>
            blockMetrics.blockCreationErrorCounter.inc
            throw ex
        }
      }
    } yield result

    Trace[F].span("onSlotEvent-validator-generating")(tracedGenerateBlock)
  }
}
