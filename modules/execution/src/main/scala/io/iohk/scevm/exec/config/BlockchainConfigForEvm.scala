package io.iohk.scevm.exec.config

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.{BlockchainConfig, EthCompatibilityConfig}
import io.iohk.scevm.domain.{BlockNumber, Nonce}
import io.iohk.scevm.exec.config.BlockchainConfigForEvm.EthForks.{
  BeforeByzantium,
  Berlin,
  Byzantium,
  Constantinople,
  Istanbul,
  London,
  Petersburg
}

import scala.math.Ordered.orderingToOrdered

/** A subset of [[io.iohk.scevm.config.BlockchainConfig]] that is required for instantiating an [[EvmConfig]]
  * Note that `accountStartNonce` is required for a [[worldState]] implementation that is used
  * by a given VM
  */
// FIXME the `xForkForBlockNumber` methods will throw in combination with `ForkBlockNumbers.Empty`
final case class BlockchainConfigForEvm(
    // ETH forks
    frontierBlockNumber: BlockNumber,
    homesteadBlockNumber: BlockNumber,
    eip150BlockNumber: BlockNumber,
    spuriousDragonBlockNumber: BlockNumber,
    byzantiumBlockNumber: BlockNumber,
    constantinopleBlockNumber: BlockNumber,
    petersburgBlockNumber: BlockNumber,
    istanbulBlockNumber: BlockNumber,
    berlinBlockNumber: BlockNumber,
    londonBlockNumber: BlockNumber,
    maxCodeSize: Option[BigInt],
    accountStartNonce: Nonce,
    // SC EVM forks
    // ...none yet
    chainId: ChainId,
    ethCompatibility: EthCompatibilityConfig
) {

  def ethForkForBlockNumber(blockNumber: BlockNumber): BlockchainConfigForEvm.EthForks.Value =
    if (blockNumber < byzantiumBlockNumber) BeforeByzantium
    else if (blockNumber < constantinopleBlockNumber) Byzantium
    else if (blockNumber < petersburgBlockNumber) Constantinople
    else if (blockNumber < istanbulBlockNumber) Petersburg
    else if (blockNumber < berlinBlockNumber) Istanbul
    else if (blockNumber < londonBlockNumber) Berlin
    else London

}

object BlockchainConfigForEvm {

  object EthForks extends Enumeration {
    type EthFork = Value
    val BeforeByzantium, Byzantium, Constantinople, Petersburg, Istanbul, Berlin, London = Value
  }

  def isEip2929Enabled(ethFork: BlockchainConfigForEvm.EthForks.Value): Boolean = ethFork >= EthForks.Berlin

  def apply(blockchainConfig: BlockchainConfig): BlockchainConfigForEvm = {
    import blockchainConfig._
    BlockchainConfigForEvm(
      frontierBlockNumber = forkBlockNumbers.frontierBlockNumber,
      homesteadBlockNumber = forkBlockNumbers.homesteadBlockNumber,
      eip150BlockNumber = forkBlockNumbers.eip150BlockNumber,
      spuriousDragonBlockNumber = forkBlockNumbers.spuriousDragonBlockNumber,
      byzantiumBlockNumber = forkBlockNumbers.byzantiumBlockNumber,
      constantinopleBlockNumber = forkBlockNumbers.constantinopleBlockNumber,
      petersburgBlockNumber = forkBlockNumbers.petersburgBlockNumber,
      istanbulBlockNumber = forkBlockNumbers.istanbulBlockNumber,
      berlinBlockNumber = forkBlockNumbers.berlinBlockNumber,
      londonBlockNumber = forkBlockNumbers.londonBlockNumber,
      maxCodeSize = maxCodeSize,
      accountStartNonce = genesisData.accountStartNonce,
      chainId = chainId,
      ethCompatibility = ethCompatibility
    )
  }

}
