package io.iohk.scevm.rpc

import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.{Gas, Token}
import io.iohk.scevm.rpc.faucet.FaucetConfig

object TestRpcConfig {
  val filterConfig: FilterConfig = FilterConfig(filterMaxBlockToFetch = 100)

  val faucetConfig: FaucetConfig = FaucetConfig.Enabled(
    faucetKey = ECDSA.PrivateKey.fromHexUnsafe("f46bf49093d585f2ea781a0bf6d83468919f547999ad91c9210256979d88eea2"),
    txGasPrice = Token(BigInt("20000000000")),
    txGasLimit = Gas(BigInt("90000")),
    txValue = Token(BigInt("1000000000000000000"))
  )

}
