package io.iohk.scevm.rpc.sidechain

import io.iohk.scevm.domain.{EpochPhase, Slot}
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainEpoch, MainchainSlot}
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}

trait EpochCalculator[F[_]] {
  def getSidechainEpoch(slot: Slot): SidechainEpoch

  def getSidechainEpochPhase(slot: Slot): EpochPhase

  def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot

  def getMainchainSlot(ts: UnixTimestamp): MainchainSlot

  def getCurrentEpoch: F[SidechainEpoch]

  def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds

  def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds

  def getFirstSidechainEpoch(mainchainEpoch: MainchainEpoch): SidechainEpoch
}
