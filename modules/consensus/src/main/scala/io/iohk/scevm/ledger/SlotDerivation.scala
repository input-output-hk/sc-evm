package io.iohk.scevm.ledger

import io.iohk.scevm.domain.Slot
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import SlotDerivation.InvalidTimeFailure

trait SlotDerivation {
  def getSlot(currentTime: UnixTimestamp): Either[InvalidTimeFailure, Slot]
  def timeToNextSlot(currentTime: UnixTimestamp): Either[InvalidTimeFailure, FiniteDuration]
}

object SlotDerivation {
  private[ledger] def getSlot(
      genesisTime: UnixTimestamp,
      givenTime: UnixTimestamp,
      slotSize: FiniteDuration
  ): Option[Slot] = {
    val timeElapsed = givenTime.millis - genesisTime.millis
    Option.when(timeElapsed >= 0)(Slot(timeElapsed / slotSize.toMillis))
  }

  sealed trait InitialisationError     extends Exception
  final case object InvalidSlotSize    extends InitialisationError
  final case object InvalidGenesisTime extends InitialisationError

  final case class InvalidTimeFailure(currentTime: Long, genesisTime: UnixTimestamp)
      extends Exception(s"Current-time=$currentTime should be after genesis-time=$genesisTime")

  def live(genesisTime: UnixTimestamp, slotDuration: FiniteDuration): Either[InitialisationError, SlotDerivation] =
    for {
      slotDuration <- Either.cond(slotDuration.toSeconds > 0, slotDuration, InvalidSlotSize)
      genesisTime  <- Either.cond(genesisTime.millis >= 0, genesisTime, InvalidGenesisTime)
    } yield new SlotDerivation {
      private val slotDurationInMs: Long = slotDuration.toMillis
      def getSlot(currentTime: UnixTimestamp): Either[InvalidTimeFailure, Slot] =
        SlotDerivation
          .getSlot(genesisTime, currentTime, slotDuration)
          .toRight(InvalidTimeFailure(currentTime.millis, genesisTime))

      def timeToNextSlot(currentTime: UnixTimestamp): Either[InvalidTimeFailure, FiniteDuration] =
        if (genesisTime <= currentTime) {
          val timeElapsed = currentTime.millis - genesisTime.millis
          Right((slotDurationInMs - (timeElapsed % slotDurationInMs)).millis)
        } else {
          Left(InvalidTimeFailure(currentTime.millis, genesisTime))
        }
    }
}
