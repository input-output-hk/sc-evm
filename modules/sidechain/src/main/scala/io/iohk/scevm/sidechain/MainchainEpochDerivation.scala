package io.iohk.scevm.sidechain

import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime.UnixTimestamp._
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}

trait MainchainEpochDerivation {
  def getMainchainEpoch(sidechainEpoch: SidechainEpoch): MainchainEpoch
  def getMainchainSlot(time: UnixTimestamp): MainchainSlot
  def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot
  def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds
}

class MainchainEpochDerivationImpl(
    obftConfig: ObftConfig,
    mainchainConfig: CardanoFollowerConfig
) extends MainchainEpochDerivation {
  private val mainchainEpochDurationMillis: Long = mainchainConfig.epochDuration.toMillis
  private val mainchainSlotDurationMillis: Long  = mainchainConfig.slotDuration.toMillis
  private val sidechainEpochDurationMillis: Long =
    (obftConfig.slotDuration * obftConfig.epochDurationInSlot).toMillis

  override def getMainchainSlot(time: UnixTimestamp): MainchainSlot = {
    val timeElapsed = time.millis - mainchainConfig.firstEpochTimestamp.millis
    if (timeElapsed >= 0) {
      MainchainSlot(mainchainConfig.firstSlot.number + timeElapsed / mainchainSlotDurationMillis)
    } else {
      throw new RuntimeException(s"Could not derive a mainchain slot for timestamp before first epoch timestamp")
    }
  }

  override def getMainchainEpoch(sidechainEpoch: SidechainEpoch): MainchainEpoch =
    // We should only have a None if the configuration is invalid
    // (i.e. the sidechain epoch is somehow before the main chain first epoch)
    getMainchainEpoch(sidechainEpochStartTs(sidechainEpoch))
      .getOrElse(throw new RuntimeException(s"Could not derive a mainchain epoch from sidechain epoch $sidechainEpoch"))

  def sidechainEpochStartTs(sidechainEpoch: SidechainEpoch): UnixTimestamp =
    (obftConfig.genesisTimestamp.millis + (sidechainEpoch.number * sidechainEpochDurationMillis)).millisToTs

  override def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot =
    getMainchainSlot(sidechainEpochStartTs(sidechainEpoch))

  override def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds =
    mainchainConfig.firstEpochTimestamp
      .add(
        mainchainConfig.epochDuration * (epoch.number - mainchainConfig.firstEpochNumber)
      )
      .toTimestampSeconds

  def getMainchainEpoch(time: UnixTimestamp): Option[MainchainEpoch] = {
    val timeElapsed = time.millis - mainchainConfig.firstEpochTimestamp.millis
    Option.when(timeElapsed >= 0)(
      MainchainEpoch(mainchainConfig.firstEpochNumber + timeElapsed / mainchainEpochDurationMillis)
    )
  }
}
