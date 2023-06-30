package io.iohk.scevm.ledger

import cats.FlatMap
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.UInt256
import io.iohk.scevm.exec.vm.InMemoryWorldState
import org.typelevel.log4cats.LoggerFactory

object BlockRewarder {

  type BlockRewarder[F[_]] = InMemoryWorldState => F[InMemoryWorldState]

  /** This function updates the state in order to pay rewards to the block producer.
    *
    * @param worldState the initial state
    * @return the state after paying the appropriate reward
    */
  def payBlockReward[F[_]: LoggerFactory: FlatMap](
      blockchainConfig: BlockchainConfig,
      blockRewardCalculator: BlockRewardCalculator
  )(worldState: InMemoryWorldState): F[InMemoryWorldState] = {
    val log                 = LoggerFactory[F].getLogger
    val blockNumber         = worldState.blockContext.number
    val blockProducerReward = blockRewardCalculator.calculateReward(worldState.blockContext)
    val blockReward         = UInt256(blockProducerReward.amount.value)
    val worldAfterProducerReward =
      if (blockReward > 0) // We don't want to risk create an empty account that would change the state root hash
        worldState.increaseAccountBalance(blockProducerReward.address, blockReward)(
          blockchainConfig
        )
      else
        worldState

    log
      .debug(
        show"Paying reward for $blockNumber at ${worldState.blockContext.slotNumber}" +
          show" of ${blockProducerReward.amount} to producer ${blockProducerReward.address}"
      )
      .as(worldAfterProducerReward)
  }

}
