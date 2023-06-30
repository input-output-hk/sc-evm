package io.iohk.scevm.db.storage

import cats.Monad
import cats.syntax.all._
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{BlockHash, ObftHeader, TransactionHash, TransactionLocation}

/** This service allows to find where is a transaction in a given chain */

trait GetTransactionBlockService[F[_]] {

  /** Find a transaction inside an alternative branch (represented by its tip hash) */
  def getTransactionLocation(stable: ObftHeader, best: BlockHash)(
      transactionHash: TransactionHash
  ): F[Option[TransactionLocation]]
}

class GetTransactionBlockServiceImpl[F[_]: Monad](
    blocksReader: BlocksReader[F],
    transactionMappingStorage: StableTransactionMappingStorage[F]
) extends GetTransactionBlockService[F] {

  /* The search for a transaction is done in two steps:
   *  - check if the transaction is in the index transactionMappingStorage
   *    * if yes then we are done and can return the result
   *  - otherwise, it means that either the transaction is in the unstable part of the
   *  chain (which is not index currently), or that it does not exist in the chain. We
   *  have to do a scan of the unstable part of the chain down to the stable part, by checking
   *  the starting block and its parents.
   */
  override def getTransactionLocation(
      stable: ObftHeader,
      branchTip: BlockHash
  )(transactionHash: TransactionHash): F[Option[TransactionLocation]] =
    transactionMappingStorage
      .getTransactionLocation(transactionHash)
      .flatMap {
        case Some(result) =>
          result.some.pure[F]
        case None =>
          fetchTransactionFromUnstablePart(stable, transactionHash)(branchTip)
      }

  private def fetchTransactionFromUnstablePart(stableHeader: ObftHeader, transactionHash: TransactionHash)(
      currentBlockHash: BlockHash
  ): F[Option[TransactionLocation]] =
    blocksReader
      .getBlock(currentBlockHash)
      .flatMap {
        case Some(block) if block.body.transactionList.exists(_.hash == transactionHash) =>
          // Found the transaction
          TransactionLocation(block.hash, block.body.transactionList.indexWhere(_.hash == transactionHash)).some.pure

        case Some(block) if block.number > (stableHeader.number.next) =>
          // did not find the transaction, look into the parent
          fetchTransactionFromUnstablePart(stableHeader, transactionHash)(block.header.parentHash)
        case _ =>
          // we don't know the block, or we reached the end of the unstable part of the chain
          none[TransactionLocation].pure
      }

}
