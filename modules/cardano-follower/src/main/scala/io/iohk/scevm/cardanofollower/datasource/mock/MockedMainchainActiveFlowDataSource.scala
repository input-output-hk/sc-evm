package io.iohk.scevm.cardanofollower.datasource.mock

import cats.Applicative
import cats.syntax.all._
import io.iohk.scevm.cardanofollower.datasource.MainchainActiveFlowDataSource
import io.iohk.scevm.trustlesssidechain.cardano.{CheckpointNft, CommitteeNft, MerkleRootNft}

class MockedMainchainActiveFlowDataSource[F[_]: Applicative] extends MainchainActiveFlowDataSource[F] {
  override def getLatestCommitteeNft(): F[Option[CommitteeNft]] =
    none[CommitteeNft].pure[F]

  override def getLatestOnChainMerkleRootNft(): F[Option[MerkleRootNft]] =
    none[MerkleRootNft].pure[F]

  override def getLatestCheckpointNft(): F[Option[CheckpointNft]] =
    none[CheckpointNft].pure[F]
}
