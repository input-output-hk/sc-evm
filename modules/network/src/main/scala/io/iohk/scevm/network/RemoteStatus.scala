package io.iohk.scevm.network

import cats.implicits.showInterpolator
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.network.p2p.messages.OBFT1

import scala.concurrent.duration.FiniteDuration

/** RemoteStatus was created to decouple status information from protocol status messages
  * (they are different versions of [[OBFT1.Status]] msg)
  */
final case class RemoteStatus private (
    networkId: Int,
    chainId: ChainId,
    genesisHash: BlockHash,
    stabilityParameter: Int,
    modeConfigHash: ByteString,
    slotDuration: FiniteDuration
) {
  override def toString: String =
    show"""RemoteStatus {
        |networkId: $networkId
        |chainId: $chainId
        |genesisHash: $genesisHash
        |stabilityParameter: $stabilityParameter
        |modeConfigHash: $modeConfigHash
        |slotDuration: $slotDuration
        |}""".stripMargin.replace("\n", " ")
}

object RemoteStatus {
  def apply(status: OBFT1.Status): RemoteStatus =
    RemoteStatus(
      networkId = status.networkId,
      genesisHash = status.genesisHash,
      stabilityParameter = status.stabilityParameter,
      modeConfigHash = status.modeConfigHash,
      chainId = status.chainId,
      slotDuration = status.slotDuration
    )
}
