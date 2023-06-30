package io.iohk.scevm.network.handshaker

import io.iohk.bytes.ByteString
import io.iohk.scevm.config.{AppConfig, BlockchainConfig}
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.network.PeerConfig
import io.iohk.scevm.utils.NodeStatusProvider

trait ObftHandshakerConfig[F[_]] {
  def nodeStatusHolder: NodeStatusProvider[F]
  def genesisHash: BlockHash
  def peerConfiguration: PeerConfig
  implicit def currentBranch: CurrentBranch.Signal[F]
  def blockchainConfig: BlockchainConfig
  def appConfig: AppConfig
  def modeConfigHash: ByteString
}
