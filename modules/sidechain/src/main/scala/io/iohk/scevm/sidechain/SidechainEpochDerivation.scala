package io.iohk.scevm.sidechain

import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.iohk.scevm.domain.{EpochPhase, Slot}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}

trait SidechainEpochDerivation[F[_]] {
  def getCurrentEpoch: F[SidechainEpoch]
  def getSidechainEpoch(slot: Slot): SidechainEpoch
  def getSidechainEpoch(time: UnixTimestamp): Option[SidechainEpoch]
  def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds
  def getSidechainEpochPhase(slot: Slot): EpochPhase
}

class SidechainEpochDerivationImpl[F[_]: SystemTime: MonadCancelThrow](
    obftConfig: ObftConfig
) extends SidechainEpochDerivation[F] {
  private val sidechainEpochDurationMillis: Long =
    (obftConfig.slotDuration * obftConfig.epochDurationInSlot).toMillis
  private val closeTransactionsBatchStart = obftConfig.epochDurationInSlot - 4 * obftConfig.stabilityParameter
  private val signingProcessStart         = obftConfig.epochDurationInSlot - 2 * obftConfig.stabilityParameter

  override def getSidechainEpoch(time: UnixTimestamp): Option[SidechainEpoch] = {
    val timeElapsed = time.millis - obftConfig.genesisTimestamp.millis
    Option.when(timeElapsed >= 0)(SidechainEpoch(timeElapsed / sidechainEpochDurationMillis))
  }

  override def getSidechainEpoch(slot: Slot): SidechainEpoch =
    SidechainEpoch((slot.number / obftConfig.epochDurationInSlot).longValue)

  override def getCurrentEpoch: F[SidechainEpoch] =
    SystemTime[F]
      .realTime()
      .map(getSidechainEpoch)
      .flatMap {
        case Some(value) => value.pure[F]
        case None =>
          new RuntimeException("Could not calculate current sidechain epoch")
            .raiseError[F, SidechainEpoch]
      }
  override def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds =
    obftConfig.genesisTimestamp
      .add(
        obftConfig.slotDuration * obftConfig.epochDurationInSlot * epoch.number
      )
      .toTimestampSeconds

  override def getSidechainEpochPhase(slot: Slot): EpochPhase = {
    val slotInEpoch = slot.number % obftConfig.epochDurationInSlot
    if (slotInEpoch < closeTransactionsBatchStart) EpochPhase.Regular
    else if (slotInEpoch < signingProcessStart) EpochPhase.ClosedTransactionBatch
    else EpochPhase.Handover
  }
}
