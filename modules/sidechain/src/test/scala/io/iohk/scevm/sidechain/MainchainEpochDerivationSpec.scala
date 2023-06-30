package io.iohk.scevm.sidechain

import cats.effect.IO
import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

class MainchainEpochDerivationSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport {

  implicit val st: SystemTime[IO] = SystemTime.liveF[IO].ioValue

  "MainchainEpochDerivation" should {

    "return None on a timestamp before mainchain genesis" in {
      val epochDerivation = new MainchainEpochDerivationImpl(
        obftConfig = baseObftConf.copy(genesisTimestamp = 10000.millisToTs),
        mainchainConfig = baseCardanoFollowerConfig.copy(firstEpochTimestamp = 10000.millisToTs)
      )

      epochDerivation.getMainchainEpoch(1000.millisToTs) shouldBe None
    }

    "return the right mainchain epoch for a sidechain epoch" in {

      // Given 10 SC epochs in a mainchain epoch and the two chain starting at the the same time
      val epochDerivation = new MainchainEpochDerivationImpl(
        // 20 seconds SC epoch
        obftConfig =
          baseObftConf.copy(genesisTimestamp = 0.millisToTs, epochDurationInSlot = 10, slotDuration = 2.second),
        mainchainConfig =
          baseCardanoFollowerConfig.copy(firstEpochTimestamp = 0.millisToTs, epochDuration = 200.seconds)
      )

      epochDerivation.getMainchainEpoch(SidechainEpoch(0)) shouldBe MainchainEpoch(0)
      epochDerivation.getMainchainEpoch(SidechainEpoch(1)) shouldBe MainchainEpoch(0)
      epochDerivation.getMainchainEpoch(SidechainEpoch(12)) shouldBe MainchainEpoch(1)
      epochDerivation.getMainchainEpoch(SidechainEpoch(25)) shouldBe MainchainEpoch(2)
    }

    "return the right MC epoch for a SC epoch with a different genesis timestamp than MC" in {

      // Given 10 SC epochs in a mainchain epoch and SC starting at epoch 3
      val epochDerivation = new MainchainEpochDerivationImpl(
        // 20 seconds SC epoch
        obftConfig =
          baseObftConf.copy(genesisTimestamp = 600000.millisToTs, epochDurationInSlot = 10, slotDuration = 2.second),
        mainchainConfig =
          baseCardanoFollowerConfig.copy(firstEpochTimestamp = 0.millisToTs, epochDuration = 200.seconds)
      )

      epochDerivation.getMainchainEpoch(SidechainEpoch(0)) shouldBe MainchainEpoch(3)
      epochDerivation.getMainchainEpoch(SidechainEpoch(1)) shouldBe MainchainEpoch(3)
      epochDerivation.getMainchainEpoch(SidechainEpoch(12)) shouldBe MainchainEpoch(4)
      epochDerivation.getMainchainEpoch(SidechainEpoch(25)) shouldBe MainchainEpoch(5)
    }

    "return the right MC epoch with firstEpochNumber different than 0" in {

      // Given 10 SC epochs in a mainchain epoch and SC starting at epoch 3
      val epochDerivation = new MainchainEpochDerivationImpl(
        // 20 seconds SC epoch
        obftConfig =
          baseObftConf.copy(genesisTimestamp = 0.millisToTs, epochDurationInSlot = 10, slotDuration = 1.second),
        mainchainConfig = baseCardanoFollowerConfig.copy(
          firstEpochNumber = 42,
          firstEpochTimestamp = 0.millisToTs,
          epochDuration = 100.seconds
        )
      )

      epochDerivation.getMainchainEpoch(SidechainEpoch(0)) shouldBe MainchainEpoch(42)
      epochDerivation.getMainchainEpoch(SidechainEpoch(16)) shouldBe MainchainEpoch(43)
    }
  }

  def baseObftConf: ObftConfig =
    ObftConfig(
      genesisTimestamp = 0.millisToTs,
      stabilityParameter = 15,
      slotDuration = 10.seconds,
      epochDurationInSlot = 190
    )
  def baseCardanoFollowerConfig: CardanoFollowerConfig = CardanoFollowerConfig(
    stabilityParameter = 2160,
    firstEpochTimestamp = 0.millisToTs,
    firstEpochNumber = 0,
    firstSlot = MainchainSlot(0),
    epochDuration = 1900.seconds,
    slotDuration = 1.seconds,
    activeSlotCoeff = BigDecimal("0.05"),
    committeeCandidateAddress = MainchainAddress("dummy"),
    fuelMintingPolicyId = PolicyId.empty,
    fuelAssetName = AssetName.empty,
    merkleRootNftPolicyId = PolicyId.empty,
    committeeNftPolicyId = PolicyId.empty,
    checkpointNftPolicyId = PolicyId.empty
  )
  lazy val obftConf1: ObftConfig = ObftConfig(
    genesisTimestamp = 1660057200000L.millisToTs,
    epochDurationInSlot = 720,
    stabilityParameter = 60,
    slotDuration = 5.second
  )
  lazy val obftConf2: ObftConfig = ObftConfig(
    genesisTimestamp = 1654563600000L.millisToTs,
    epochDurationInSlot = 60 * 12,
    stabilityParameter = 60,
    slotDuration = 5.seconds
  )
  lazy val followerConfig: CardanoFollowerConfig = baseCardanoFollowerConfig.copy(
    stabilityParameter = 36,
    firstEpochTimestamp = 1654563600000L.millisToTs,
    firstEpochNumber = 1,
    firstSlot = MainchainSlot(360),
    epochDuration = 7200.seconds,
    slotDuration = 1.seconds,
    activeSlotCoeff = BigDecimal("0.05")
  )
}
