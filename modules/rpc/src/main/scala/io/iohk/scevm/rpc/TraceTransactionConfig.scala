package io.iohk.scevm.rpc

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

final case class TraceTransactionConfig(debugTraceTxDefaultTimeout: FiniteDuration)

object TraceTransactionConfig {
  def fromConfig(config: Config): TraceTransactionConfig = {
    val defaultTimeout = config.getDuration("network.rpc.trace-transaction.default-timeout").toScala
    TraceTransactionConfig(debugTraceTxDefaultTimeout = defaultTimeout)
  }
}
