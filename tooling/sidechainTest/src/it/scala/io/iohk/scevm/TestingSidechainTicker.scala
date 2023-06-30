package io.iohk.scevm

import io.iohk.scevm.domain.{Slot, Tick}
import io.iohk.scevm.sync.tick.SlotTick
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

import scala.concurrent.duration.FiniteDuration

class TestingSidechainTicker[F[_]](initialTimestamp: UnixTimestamp, numberOfSlots: Int) extends SlotTick[F] {

  override def ticker(slotDuration: FiniteDuration): fs2.Stream[F, Tick] =
    fs2.Stream
      .range[F, Int](1, numberOfSlots)
      .map(index => Tick(Slot(index), initialTimestamp.add(slotDuration * index)))
}
