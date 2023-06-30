package io.iohk.scevm.sync.tick

import cats.effect.{Async, Sync, Temporal}
import cats.syntax.all._
import cats.{Applicative, MonadError}
import io.iohk.scevm.domain.Tick
import io.iohk.scevm.ledger.SlotDerivation
import io.iohk.scevm.utils.StreamUtils.RichFs2Stream
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

import scala.concurrent.duration._

trait SlotTick[F[_]] {
  def ticker(slotDuration: FiniteDuration): fs2.Stream[F, Tick]
}

class SlotTickImpl[F[_]: Async: SystemTime](slotDerivation: SlotDerivation) extends SlotTick[F] {
  implicit private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  /** Start a tick mechanism that corresponds to the change of slot
    * @param slotDuration the frequency
    * @return stream of slot evens
    */
  override def ticker(slotDuration: FiniteDuration): fs2.Stream[F, Tick] =
    fs2.Stream
      .eval(SystemTime[F].realTime())
      .flatMap { time =>
        fs2.Stream
          .unfoldEval(time) { time =>
            for {
              slot <- MonadError[F, Throwable].fromEither(slotDerivation.getSlot(time))
              sick <- nextTick(slot.number)
            } yield generateNextState(sick)
          }
      }
      .evalScan1[F, Tick] { case (previous, current) =>
        if (current.slot.number - previous.slot.number > 1) {
          Logger[F].warn(s"Missed slots between $current and $previous").as(current)
        } else {
          Applicative[F].pure(current)
        }
      }

  private def generateNextState(sick: Tick): Option[(Tick, UnixTimestamp)] =
    Some((sick, sick.timestamp))

  /** Sleep until the beginning of the next slot.
    * As Temporal.sleep doesn't guarantee that the amount of sleep will be at least the number specified (it could be slightly less),
    * a check is performed to see if we have reach the expected time.
    *
    * @param initialTime The current time
    * @return the time after sleeping until the next slot or the initial time if there is no need to sleep
    */
  private def sleepUntilNextSlot(initialTime: UnixTimestamp): F[UnixTimestamp] =
    for {
      millisBeforeNextSlot <- MonadError[F, Throwable].fromEither(slotDerivation.timeToNextSlot(initialTime))
      _                    <- Temporal[F].sleep(millisBeforeNextSlot)
      nextSlotTime <- SystemTime[F].realTime().flatMap { timeAfterSleep =>
                        if (initialTime.add(millisBeforeNextSlot) > timeAfterSleep) {
                          sleepUntilNextSlot(timeAfterSleep)
                        } else {
                          Sync[F].pure(timeAfterSleep)
                        }
                      }
    } yield nextSlotTime

  /** Generate the next Tick.
    *
    * If we are in the same slot than the previous Tick, it means that the Tick was consumed quickly.
    * In that case, we want to wait until the beginning of the next slot.
    *
    * If we are in a different slot than the previous one, then it means that the consumption of the previous slot has overflowed the current slot.
    * So there is no need to wait, as we are already in a different slot.
    *
    * Note: if every consumption of Tick take a little more than `slot-duration`,
    * then at a certain point, a Tick will be skipped.
    *
    * @param previousSlotNumber the number of the previous slot
    * @return the new Tick
    */
  private def nextTick(previousSlotNumber: BigInt): F[Tick] =
    for {
      time        <- SystemTime[F].realTime()
      currentSlot <- MonadError[F, Throwable].fromEither(slotDerivation.getSlot(time))
      sick <- if (currentSlot.number == previousSlotNumber) {
                for {
                  nextSlotTime <- sleepUntilNextSlot(time)
                  slot         <- MonadError[F, Throwable].fromEither(slotDerivation.getSlot(nextSlotTime))
                } yield Tick(slot, nextSlotTime)
              } else {
                Sync[F].pure(Tick(currentSlot, time))
              }
    } yield sick
}
