package io.iohk.scevm

import com.typesafe.config.Config
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.ConfigOps._
import io.iohk.scevm.config.{AppConfig, BlockchainConfig, KeyStoreConfig, StandaloneBlockchainConfig}
import io.iohk.scevm.consensus.pos.PoSConfig
import io.iohk.scevm.db.DataSourceConfig
import io.iohk.scevm.exec.mempool.MempoolConfig
import io.iohk.scevm.extvm.VmConfig
import io.iohk.scevm.metrics.InstrumentationConfig
import io.iohk.scevm.network.{BranchFetcherConfig, NetworkConfig, SyncConfig}
import io.iohk.scevm.rpc.faucet.FaucetConfig
import io.iohk.scevm.rpc.{FilterConfig, JsonRpcConfig, JsonRpcHttpServerConfig, TraceTransactionConfig}
import io.iohk.scevm.sidechain.{ExtraSidechainConfig, SidechainBlockchainConfig}

/** Contains all the configurations of a node
  * Note:
  * - BlockchainConfig contains only structural parameters
  * - ExtraSidechainConfig contains only non-structural parameters for the sidechain mode
  */
final case class ScEvmConfig private (
    networkName: String,
    keyStoreConfig: KeyStoreConfig,
    blockchainConfig: BlockchainConfig,
    appConfig: AppConfig,
    filterConfig: FilterConfig,
    posConfig: PoSConfig,
    syncConfig: SyncConfig,
    dbConfig: DataSourceConfig,
    networkConfig: NetworkConfig,
    vmConfig: VmConfig,
    mempoolConfig: MempoolConfig,
    jsonRpcHttpServerConfig: JsonRpcHttpServerConfig,
    jsonRpcConfig: JsonRpcConfig,
    traceTransactionConfig: TraceTransactionConfig,
    faucetConfig: FaucetConfig,
    instrumentationConfig: InstrumentationConfig,
    branchFetcherConfig: BranchFetcherConfig,
    extraSidechainConfig: ExtraSidechainConfig
)

object ScEvmConfig {
  def fromConfig(config: Config): ScEvmConfig = {
    val networkName = config.getString("blockchain.network")

    val rawBlockchainConfig = config.getConfig("blockchain")
    val (blockchainConfig, poSConfig) = rawBlockchainConfig.getStringOpt("chain-mode") match {
      case Some("sidechain") =>
        val sidechainConfig = SidechainBlockchainConfig.fromRawConfig(rawBlockchainConfig)
        val poSConfig = sidechainConfig.signingScheme match {
          case ECDSA  => PoSConfig.fromConfig[ECDSA](config)
          case scheme => throw new IllegalArgumentException(s"Unrecognized cross-chain signature scheme: $scheme")
        }
        (sidechainConfig, poSConfig)
      case Some("standalone") =>
        val poSConfig        = PoSConfig.fromConfig[ECDSA](config)
        val standaloneConfig = StandaloneBlockchainConfig.fromRawConfig(rawBlockchainConfig)
        (standaloneConfig, poSConfig)
      case _ => throw new Exception("'chain-mode' config missing. It has to be 'standalone' or 'sidechain'")
    }
    val extraSidechainConfig = ExtraSidechainConfig.fromConfig(config, blockchainConfig)

    new ScEvmConfig(
      networkName = networkName,
      keyStoreConfig = KeyStoreConfig.fromConfig(config),
      blockchainConfig = blockchainConfig,
      appConfig = AppConfig.fromConfig(config),
      filterConfig = FilterConfig.fromConfig(config),
      posConfig = poSConfig,
      syncConfig = SyncConfig.fromConfig(config),
      dbConfig = DataSourceConfig.fromConfig(config),
      networkConfig = NetworkConfig.fromConfig(config),
      vmConfig = VmConfig.fromRawConfig(config),
      mempoolConfig = MempoolConfig.fromConfig(config),
      jsonRpcHttpServerConfig = JsonRpcHttpServerConfig.fromConfig(config),
      jsonRpcConfig = JsonRpcConfig.fromConfig(config),
      traceTransactionConfig = TraceTransactionConfig.fromConfig(config),
      faucetConfig = FaucetConfig.fromRawConfig(config),
      instrumentationConfig = InstrumentationConfig.fromConfig(config),
      branchFetcherConfig = BranchFetcherConfig.fromConfig(config),
      extraSidechainConfig = extraSidechainConfig
    )
  }
}
