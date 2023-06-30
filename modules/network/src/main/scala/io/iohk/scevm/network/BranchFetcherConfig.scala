package io.iohk.scevm.network

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

/** @param maxBlocksPerMessage Maximum number of blocks to be requested in a single message
  * @param requestTimeout Timeout for a single message to a peer
  * @param subscriptionQueueSize Subscription queue size for fs2.Topic to handle responses. Low value can block other consumers.
  *                              See `io.iohk.scevm.network.SyncConfig.messageSourceBuffer`
  */
final case class BranchFetcherConfig(
    maxBlocksPerMessage: Int,
    requestTimeout: FiniteDuration,
    subscriptionQueueSize: Int
)

object BranchFetcherConfig {
  def fromConfig(config: Config): BranchFetcherConfig = {
    val branchFetcherConfig = config.getConfig("branchFetcher")
    BranchFetcherConfig(
      maxBlocksPerMessage = branchFetcherConfig.getInt("max-blocks-per-message"),
      requestTimeout = branchFetcherConfig.getDuration("request-timeout").toScala,
      subscriptionQueueSize = branchFetcherConfig.getInt("subscription-queue-size")
    )
  }
}
