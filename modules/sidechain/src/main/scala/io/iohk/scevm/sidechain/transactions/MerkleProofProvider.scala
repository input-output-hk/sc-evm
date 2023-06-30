package io.iohk.scevm.sidechain.transactions

import cats.Show
import cats.data.{NonEmptyList, OptionT}
import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.iohk.scevm.domain.UInt256
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry.NewMerkleRoot
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.{BridgeContract, CombinedMerkleProofWithDetails}
import io.iohk.scevm.sidechain.transactions.merkletree.MerkleProof

trait MerkleProofProvider[F[_]] {
  def getProof(world: WorldType)(epoch: SidechainEpoch, txId: OutgoingTxId): F[Option[CombinedMerkleProofWithDetails]]

}

class MerkleProofProviderImpl[F[_]: MonadCancelThrow](
    bridgeContract: BridgeContract[EvmCall[F, *]]
) extends MerkleProofProvider[F] {

  def getProof(world: WorldType)(epoch: SidechainEpoch, txId: OutgoingTxId): F[Option[CombinedMerkleProofWithDetails]] =
    (for {
      txBatch                      <- getTransactionBatchForEpoch(world, epoch)
      chainEntry                   <- OptionT(bridgeContract.getMerkleRootChainEntry(epoch).run(world))
      previousMerkleRootChainEntry <- OptionT.liftF(getPreviousMerkleRootChainEntry(world, chainEntry))
      txBatchEntry                 <- OptionT.fromOption(txBatch.find(_.txIndex == txId))
      combinedMp                   <- calculateCombinedMpOpt(txBatch, previousMerkleRootChainEntry, txBatchEntry)
    } yield combinedMp).value

  private def calculateCombinedMpOpt(
      txBatch: NonEmptyList[OutgoingTransaction],
      previousMerkleRootChainEntry: Option[NewMerkleRoot],
      txBatchEntry: OutgoingTransaction
  ) =
    OptionT.liftF(calculateCombinedMP(txBatch, txBatchEntry, previousMerkleRootChainEntry.map(_.rootHash)))

  private def getPreviousMerkleRootChainEntry(
      world: WorldType,
      chainEntry: NewMerkleRoot
  ) =
    OptionT
      .fromOption(chainEntry.previousEntryEpoch)
      .semiflatMap { value =>
        OptionT(bridgeContract.getMerkleRootChainEntry(value).run(world))
          .collect { case e: MerkleRootEntry.NewMerkleRoot => e }
          .getOrElseF(
            new IllegalStateException(
              show"Couldn't get the previous value for following merkle root chain entry $chainEntry"
            )
              .raiseError[F, MerkleRootEntry.NewMerkleRoot]
          )
      }
      .value

  private def getTransactionBatchForEpoch(world: WorldType, epoch: SidechainEpoch) =
    OptionT(
      bridgeContract
        .getTransactionsInBatch(epoch.number)
        .run(world)
        .map(seq => NonEmptyList.fromList(seq.toList))
    )

  private def calculateCombinedMP(
      txBatch: NonEmptyList[OutgoingTransaction],
      txBatchEntry: OutgoingTransaction,
      previousMerkleRootHash: Option[merkletree.RootHash]
  ) = {
    val tree = TransactionsMerkleTree(previousMerkleRootHash, txBatch)
    TransactionsMerkleTree.createProof(tree, txBatchEntry, previousMerkleRootHash) match {
      case Some(proof) =>
        CombinedMerkleProofWithDetails(tree.rootHash, proof).pure
      case None =>
        new IllegalStateException(s"Couldn't calculate merkle proof for transaction from batch: $txBatch")
          .raiseError[F, CombinedMerkleProofWithDetails]
    }
  }
}

object MerkleProofProvider {
  final case class CombinedMerkleProof(
      transaction: MerkleTreeEntry,
      merkleProof: MerkleProof
  )
  object CombinedMerkleProof {
    implicit val show: Show[CombinedMerkleProof] = cats.derived.semiauto.show
  }
  final case class CombinedMerkleProofWithDetails(
      currentMerkleRoot: merkletree.RootHash,
      combined: CombinedMerkleProof
  )
  object CombinedMerkleProofWithDetails {
    implicit val show: Show[CombinedMerkleProofWithDetails] = cats.derived.semiauto.show
  }

  trait BridgeContract[F[_]] {
    def getTransactionsInBatch(batch: UInt256): F[Seq[OutgoingTransaction]]

    def getMerkleRootChainEntry(epoch: SidechainEpoch): F[Option[MerkleRootEntry.NewMerkleRoot]]
  }
}
