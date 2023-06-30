package io.iohk.scevm.db.storage

import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftBody, ObftHeader}

trait BlocksReader[F[_]] {
  def getBlockHeader(hash: BlockHash): F[Option[ObftHeader]]
  def getBlockBody(hash: BlockHash): F[Option[ObftBody]]
  def getBlock(hash: BlockHash): F[Option[ObftBlock]]
}

object BlocksReader {
  def apply[F[_]](implicit ev: BlocksReader[F]): BlocksReader[F] = ev
}
