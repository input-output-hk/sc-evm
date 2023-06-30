package io.iohk.scevm.rpc

import com.typesafe.config.Config

final case class JsonRpcConfig(
    eth: Boolean,
    net: Boolean,
    web3: Boolean,
    personal: Boolean,
    faucet: Boolean,
    sidechain: Boolean,
    sanity: Boolean,
    txPool: Boolean,
    inspect: Boolean,
    scevm: Boolean,
    debug: Boolean
)
object JsonRpcConfig {
  def fromConfig(config: Config): JsonRpcConfig = {
    val rpcConfig = config.getConfig("network.rpc.apis")

    JsonRpcConfig(
      eth = rpcConfig.getBoolean(Eth),
      net = rpcConfig.getBoolean(Net),
      web3 = rpcConfig.getBoolean(Web3),
      personal = rpcConfig.getBoolean(Personal),
      faucet = rpcConfig.getBoolean(Faucet),
      sidechain = rpcConfig.getBoolean(Sidechain),
      sanity = rpcConfig.getBoolean(Sanity),
      txPool = rpcConfig.getBoolean(TxPool),
      inspect = rpcConfig.getBoolean(Inspect),
      scevm = rpcConfig.getBoolean(ScEvm),
      debug = rpcConfig.getBoolean(Debug)
    )
  }
  val Eth       = "eth"
  val Net       = "net"
  val Web3      = "web3"
  val Personal  = "personal"
  val Faucet    = "faucet"
  val Sidechain = "sidechain"
  val Sanity    = "sanity"
  val TxPool    = "txpool"
  val Inspect   = "inspect"
  val ScEvm     = "scevm"
  val Debug     = "debug"
}
