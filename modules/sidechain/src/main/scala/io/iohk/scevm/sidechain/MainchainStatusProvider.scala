package io.iohk.scevm.sidechain

import io.iohk.scevm.sidechain.MainchainStatusProvider.MainchainStatus
import io.iohk.scevm.trustlesssidechain.cardano.MainchainBlockInfo

trait MainchainStatusProvider[F[_]] {
  def getStatus: F[MainchainStatus]
}
object MainchainStatusProvider {
  final case class MainchainStatus(latestBlock: Option[MainchainBlockInfo], stableBlock: Option[MainchainBlockInfo])
}
