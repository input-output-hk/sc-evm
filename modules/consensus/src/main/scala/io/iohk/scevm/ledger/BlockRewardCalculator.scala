package io.iohk.scevm.ledger

import io.iohk.scevm.domain.{Address, BlockContext, Token}

import BlockRewardCalculator.BlockReward

trait BlockRewardCalculator {
  def calculateReward(blockContext: BlockContext): BlockReward
}

object BlockRewardCalculator {

  def apply(blockReward: BigInt, rewardAddressProvider: RewardAddressProvider): BlockRewardCalculator =
    header => BlockReward(Token(blockReward), rewardAddressProvider.getRewardAddress(header))

  final case class BlockReward(amount: Token, address: Address)
}
