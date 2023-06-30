package io.iohk.scevm.testing

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.{AppConfig, ForkBlockNumbers, StandaloneBlockchainConfig, _}
import io.iohk.scevm.domain.{Address, Nonce, ObftGenesisAccount, ObftGenesisData, UInt256}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp

import scala.concurrent.duration.DurationInt

object TestCoreConfigs {
  val chainId: ChainId = ChainId(61)

  val blockchainConfig: StandaloneBlockchainConfig = StandaloneBlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Full,
    maxCodeSize = None,
    genesisData = ObftGenesisData(
      Address("0x0000000000000000000000000000000000000000"),
      9048576,
      (1604538496L * 1000).millisToTs,
      Map(
        Address("0x075b073eaa848fc14de2fd9c2a18d97a4783d84d") -> ObftGenesisAccount(
          UInt256.MaxValue,
          None,
          None,
          Map.empty
        )
      ),
      accountStartNonce = Nonce.Zero
    ),
    chainId = chainId,
    networkId = 42,
    ethCompatibility = EthCompatibilityConfig(1, 0),
    monetaryPolicyConfig = MonetaryPolicyConfig(BigInt("5000000000000000000"), None),
    slotDuration = 3.seconds,
    stabilityParameter = 10,
    IndexedSeq.empty
  )

  val appConfig: AppConfig = AppConfig(
    clientId = "scevm-core/v0.3.0-SNAPSHOT-d5b06a7/linux-amd64/adoptopenjdk-openjdk64bitservervm-java-11.0.11",
    clientVersion = "scevm-core/v0.3.0-SNAPSHOT-d5b06a7/linux-amd64/adoptopenjdk-openjdk64bitservervm-java-11.0.11",
    nodeKeyFile = "/tmp/scevm-test/node.key"
  )

}
