package io.iohk.scevm.cardanofollower.datasource

import io.iohk.scevm.trustlesssidechain.cardano._

trait MainchainActiveFlowDataSource[F[_]] {
  def getLatestCheckpointNft(): F[Option[CheckpointNft]]

  def getLatestCommitteeNft(): F[Option[CommitteeNft]]

  def getLatestOnChainMerkleRootNft(): F[Option[MerkleRootNft]]
}
