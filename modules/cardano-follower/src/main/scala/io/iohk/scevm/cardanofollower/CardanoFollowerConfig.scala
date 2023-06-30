package io.iohk.scevm.cardanofollower

import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

import scala.concurrent.duration.FiniteDuration

final case class CardanoFollowerConfig(
    stabilityParameter: Long,
    firstEpochTimestamp: UnixTimestamp,
    firstEpochNumber: Long,
    firstSlot: MainchainSlot,
    epochDuration: FiniteDuration,
    slotDuration: FiniteDuration,
    activeSlotCoeff: BigDecimal,
    committeeCandidateAddress: MainchainAddress,
    fuelMintingPolicyId: PolicyId,
    fuelAssetName: AssetName,
    merkleRootNftPolicyId: PolicyId,
    committeeNftPolicyId: PolicyId,
    checkpointNftPolicyId: PolicyId
)
