package io.iohk.scevm.sync.tick

import cats.effect.kernel.Outcome.Succeeded
import cats.effect.testkit.TestControl
import cats.effect.{Clock, IO, Ref, Temporal}
import io.iohk.scevm.domain.{Slot, Tick}
import io.iohk.scevm.ledger.SlotDerivation
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp._
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration._

class SlotTickerSpec
    extends AsyncWordSpec
    with IOSupport
    with ScalaFutures
    with Matchers
    with IntegrationPatience
    with EitherValues {

  private val slotDuration = 100.seconds
  private val genesisTime  = 200.seconds.toMillis.millisToTs

  private def program(onNext: Tick => IO[Unit]) =
    for {
      st <- SystemTime.liveF[IO]
      ticker =
        new SlotTickImpl[IO](SlotDerivation.live(genesisTime, slotDuration).value)(Temporal[IO], st)
          .ticker(slotDuration)
      _ <- ticker.evalMap(onNext).compile.drain
    } yield ()

  "should adjust and emit ticks on fixed intervals" in {
    (for {
      observedEvents <- Ref.of[IO, List[Tick]](List.empty)
      startupJitter   = (slotDuration.toMillis / 3).millis
      control <-
        TestControl.execute(
          Clock[IO].sleep(genesisTime.millis.millis + startupJitter) >> program(slot =>
            observedEvents.update(_ :+ slot)
          )
        )
      _ <- control.tick
      _ <- control.nextInterval.flatMap(control.advanceAndTick)
      _ <- observedEvents.get.map(events => events shouldBe empty)
      // we are in the middle of the previous slot so we advance by less than slotDuration
      _ <- control.advanceAndTick(slotDuration - startupJitter)
      _ <- executeTicks(control)(times = 3, tickDuration = slotDuration)
      _ <- observedEvents.get.map(events => events shouldBe expectedSlots(0.millis).take(4))
      _ <- control.results.map(r => r shouldBe None)
    } yield succeed).ioValue
  }

  "should skip first n slots" in {
    (for {
      observedEvents <- Ref.of[IO, List[Tick]](List.empty)
      n               = 10
      startupJitter   = n * slotDuration
      control <-
        TestControl.execute(
          Clock[IO].sleep(genesisTime.millis.millis + startupJitter) >> program(slot =>
            observedEvents.update(_ :+ slot)
          )
        )
      _ <- control.tick
      _ <- control.nextInterval.flatMap(control.advanceAndTick)
      _ <- executeTicks(control)(times = 5, tickDuration = slotDuration)
      _ <- observedEvents.get.map(events => events shouldBe expectedSlots(0.millis).slice(10, 15))
      _ <- control.results.map(r => r shouldBe None)
    } yield succeed).ioValue
  }

  "should skip missed periods" in {
    (for {
      observedEvents <- Ref.of[IO, List[Tick]](List.empty)
      delay           = slotDuration / 4
      control <-
        TestControl.execute(
          Clock[IO].sleep(genesisTime.millis.millis) >> program { slot =>
            observedEvents.update(_ :+ slot)
          }
        )
      _           <- control.tick
      _           <- control.nextInterval.flatMap(control.advanceAndTick)
      _           <- observedEvents.get.map(events => events shouldBe empty)
      _           <- executeTicks(control)(times = 4, tickDuration = slotDuration + delay)
      fifthElement = Tick(Slot(5), genesisTime.add(4 * (slotDuration + delay)))
      _ <- observedEvents.get.map(events =>
             events shouldBe (expectedSlots(delay)
               .take(3) :+ fifthElement)
           )
      _ <- control.advanceAndTick(2 * slotDuration)
      _ <- observedEvents.get.map(events =>
             events shouldBe (expectedSlots(delay)
               .take(3) ++ List(fifthElement, Tick(Slot(7), fifthElement.timestamp.add(2 * slotDuration))))
           )
      _ <- control.results.map(r => r shouldBe None)
    } yield succeed).ioValue
  }

  "handle slow consumption" in {
    // Slot1 is not impacted by a previous slow consumption of SlotEvent, but the following slots are impacted.
    // Indeed, if slot1 is processed in 101 seconds (slot-duration = 100 seconds),
    // then the computation will be done during this entire slot and it will also take 1 second of the second slot.
    // But we don't want to skip the second slot, we still have 99 seconds left to perform some computations.
    val slot1 = Tick(Slot(1), genesisTime.add(slotDuration))
    val slot2 = Tick(Slot(2), genesisTime.add(slotDuration * 2 + 1.second))
    val slot3 = Tick(Slot(3), genesisTime.add(slotDuration * 3 + 2.second))

    val delay = 1.second

    val stream = for {
      st <- SystemTime.liveF[IO]
      ticker =
        new SlotTickImpl[IO](SlotDerivation.live(genesisTime, slotDuration).value)(Temporal[IO], st)
          .ticker(slotDuration)
      res <- ticker
               .evalTap(_ => Clock[IO].sleep(slotDuration + delay)) // Simulate slow consumption of SlotEvent
               .take(3)
               .compile
               .toList
    } yield res

    TestControl
      .execute(Clock[IO].sleep(genesisTime.millis.millis) >> stream)
      .flatMap(control => control.tickAll >> control.results)
      .ioValue shouldBe Some(Succeeded(List(slot1, slot2, slot3)))
  }

  private def executeTicks(control: TestControl[Unit])(times: Int, tickDuration: FiniteDuration): IO[Unit] =
    if (times > 1) {
      control.advanceAndTick(tickDuration) >> executeTicks(control)(times - 1, tickDuration)
    } else {
      control.advanceAndTick(tickDuration)
    }

  private def expectedSlots(delay: FiniteDuration) =
    LazyList.unfold(Tick(Slot(0), genesisTime)) { s =>
      val event = s.copy(slot = Slot(s.slot.number + 1), timestamp = s.timestamp.add(slotDuration).add(delay))
      Some((event, event))
    }
}
