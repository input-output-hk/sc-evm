package io.iohk.scevm.sidechain

import cats.effect.IO
import fs2.concurrent.SignallingRef
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{BlockNumber, EpochPhase, Slot, Tick}
import io.iohk.scevm.testing.{IOSupport, NormalPatience, fixtures}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class PreventStartInHandoverTickerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with EitherValues
    with IOSupport
    with NormalPatience {

  private val notGenesis         = fixtures.ValidBlock.header
  private val genesis            = notGenesis.copy(number = BlockNumber(0))
  private val firstSlot          = 200
  private val initialSlotsNumber = 100
  private val ticksStream = fs2.Stream
    .iterate(firstSlot)(_ + 1)
    .take(initialSlotsNumber)
    .map(i => Tick(Slot(i), UnixTimestamp(i * 1000)))

  "when epoch phase is Handover, filters out slots as long as the best header is at genesis" in {
    val epochDerivation             = mkEpochDerivation(_ => EpochPhase.Handover)
    val switchFromGenesisAfterSlots = 30
    (for {
      cb <- SignallingRef[IO].of(CurrentBranch(genesis, genesis))
      slots <- IO.pure(
                 ticksStream.evalTap(tick =>
                   if (tick.slot.number == firstSlot + switchFromGenesisAfterSlots)
                     cb.set(CurrentBranch(genesis, notGenesis))
                   else IO.unit
                 )
               )
      ticker         = PreventStartInHandoverTicker(slots, cb, epochDerivation)
      filteredSlots <- ticker.compile.toList
    } yield {
      filteredSlots.head.slot shouldBe Slot(firstSlot + switchFromGenesisAfterSlots)
      (filteredSlots should have).length(initialSlotsNumber - switchFromGenesisAfterSlots)
    }).ioValue
  }

  "when the best header as genesis, filters out slots as long as the phase is Handover" in {
    val slotsBeforeChangingFromHandoverToRegular = 70
    val epochDerivation =
      mkEpochDerivation(s =>
        if (s.number <= firstSlot + slotsBeforeChangingFromHandoverToRegular)
          EpochPhase.Handover
        else
          EpochPhase.Regular
      )
    (for {
      cb            <- SignallingRef[IO].of(CurrentBranch(genesis, genesis))
      ticks         <- IO.pure(ticksStream)
      ticker         = PreventStartInHandoverTicker(ticks, cb, epochDerivation)
      filteredSlots <- ticker.compile.toList
    } yield {
      filteredSlots.head.slot shouldBe Slot(firstSlot + slotsBeforeChangingFromHandoverToRegular + 1)
      (filteredSlots should have).length(initialSlotsNumber - slotsBeforeChangingFromHandoverToRegular - 1)
    }).ioValue
  }

  def mkEpochDerivation(getPhase: Slot => EpochPhase): SidechainEpochDerivation[IO] =
    new SidechainEpochDerivation[IO] {
      override def getCurrentEpoch: IO[SidechainEpoch] = ???

      override def getSidechainEpoch(slot: Slot): SidechainEpoch = ???

      override def getSidechainEpoch(time: SystemTime.UnixTimestamp): Option[SidechainEpoch] = ???

      override def getSidechainEpochStartTime(epoch: SidechainEpoch): SystemTime.TimestampSeconds = ???

      override def getSidechainEpochPhase(slot: Slot): EpochPhase = getPhase(slot)
    }
}
