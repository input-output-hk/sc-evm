package io.iohk.scevm.exec.mempool

import com.typesafe.config.Config

final case class MempoolConfig(transactionTtlRounds: Int, transactionPoolSize: Int)

object MempoolConfig {
  def fromConfig(config: Config): MempoolConfig = {
    val poSConfig                 = config.getConfig("pos")
    val transactionTtlRounds: Int = poSConfig.getInt("transaction-ttl-rounds-u")
    val transactionPoolSize: Int  = poSConfig.getInt("transaction-pool-size")
    MempoolConfig(transactionTtlRounds, transactionPoolSize)
  }
}
