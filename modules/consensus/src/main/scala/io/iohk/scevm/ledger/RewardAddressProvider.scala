package io.iohk.scevm.ledger

import io.iohk.scevm.domain.{Address, BlockContext}

trait RewardAddressProvider {
  def getRewardAddress(blockContext: BlockContext): Address
}

object RewardAddressProvider {
  def apply(rewardAddress: Option[Address]): RewardAddressProvider =
    rewardAddress match {
      case Some(address) => new FixedRewardAddressProvider(address)
      case None          => new BeneficiaryRewardAddressProvider
    }
}

class FixedRewardAddressProvider(rewardAddress: Address) extends RewardAddressProvider {
  def getRewardAddress(blockContext: BlockContext): Address = rewardAddress
}

class BeneficiaryRewardAddressProvider extends RewardAddressProvider {
  def getRewardAddress(blockContext: BlockContext): Address = blockContext.coinbase
}
