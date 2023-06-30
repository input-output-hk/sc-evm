package io.iohk.scevm.rpc.faucet

import com.typesafe.config.Config
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.{Gas, SidechainPrivateKey, Token}

sealed trait FaucetConfig
object FaucetConfig {
  case object Disabled extends FaucetConfig
  final case class Enabled(
      faucetKey: SidechainPrivateKey,
      txGasPrice: Token,
      txGasLimit: Gas,
      txValue: Token
  ) extends FaucetConfig

  def fromRawConfig(config: Config): FaucetConfig =
    if (config.hasPath("faucet")) {
      val rawFaucetConfig = config.getConfig("faucet")
      Enabled(
        faucetKey = ECDSA.PrivateKey.fromHexUnsafe(rawFaucetConfig.getString("private-key")),
        txGasPrice = Token(rawFaucetConfig.getLong("tx-gas-price")),
        txGasLimit = Gas(rawFaucetConfig.getLong("tx-gas-limit")),
        txValue = Token(rawFaucetConfig.getLong("tx-value"))
      )
    } else {
      FaucetConfig.Disabled
    }
}
