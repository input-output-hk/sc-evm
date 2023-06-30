package io.iohk.scevm.sidechain

import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.domain.Slot
import io.iohk.scevm.trustlesssidechain.cardano.MainchainSlot

import scala.concurrent.duration.FiniteDuration

trait MainchainSlotDerivation {
  def getMainchainSlot(slot: Slot): MainchainSlot
}

class MainchainSlotDerivationImpl(config: CardanoFollowerConfig, sidechainSlotDuration: FiniteDuration)
    extends MainchainSlotDerivation {
  override def getMainchainSlot(slot: Slot): MainchainSlot = {
    val timeFromTheBeginningInSeconds = slot.number * sidechainSlotDuration.toSeconds
    val slotsFromTheBeginning         = timeFromTheBeginningInSeconds / config.slotDuration.toSeconds
    MainchainSlot(config.firstSlot.number + slotsFromTheBeginning.longValue)
  }
}
