package io.iohk.scevm.exec.vm

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.EthCompatibilityConfig
import io.iohk.scevm.domain.{BlockNumber, Nonce, UInt256}
import io.iohk.scevm.exec.config.BlockchainConfigForEvm

object fixtures {

  val ConstantinopleBlockNumber: BlockNumber = BlockNumber(200)
  val PetersburgBlockNumber: BlockNumber     = BlockNumber(400)
  val IstanbulBlockNumber: BlockNumber       = BlockNumber(600)
  val BerlinBlockNumber: BlockNumber         = BlockNumber(700)
  val LondonBlockNumber: BlockNumber         = BlockNumber(800)

  val blockchainConfig: BlockchainConfigForEvm = BlockchainConfigForEvm(
    // block numbers are irrelevant
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(0),
    eip150BlockNumber = BlockNumber(0),
    spuriousDragonBlockNumber = BlockNumber(0),
    byzantiumBlockNumber = BlockNumber(0),
    constantinopleBlockNumber = ConstantinopleBlockNumber,
    petersburgBlockNumber = PetersburgBlockNumber,
    istanbulBlockNumber = IstanbulBlockNumber,
    berlinBlockNumber = BerlinBlockNumber,
    londonBlockNumber = LondonBlockNumber,
    maxCodeSize = None,
    accountStartNonce = Nonce.Zero,
    chainId = ChainId(0x3d),
    ethCompatibility = EthCompatibilityConfig(
      difficulty = UInt256(1),
      blockBaseFee = UInt256(0)
    )
  )

}
