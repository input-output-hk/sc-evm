package io.iohk.scevm.db.storage

import cats.Applicative
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftBody, ObftHeader}

trait BlocksWriter[F[_]] {

  /** Attempts to store a new block in the unstable part of the blockchain
    * @param block the new block to be added. No validation of the block is carried out here.
    *              The block must be pre-validated (valid author, valid number) to prevent attacks
    * @return the DB query to be executed
    */
  def insertBlock(block: ObftBlock)(implicit apF: Applicative[F]): F[Unit] =
    apF.productR(insertHeader(block.header))(insertBody(block.hash, block.body))

  def insertHeader(header: ObftHeader): F[Unit]

  def insertBody(blockHash: BlockHash, body: ObftBody): F[Unit]
}

object BlocksWriter {
  def apply[F[_]](implicit ev: BlocksWriter[F]): BlocksWriter[F] = ev
}
