package io.iohk.scevm.sidechain.transactions

import cats.data.NonEmptyList
import io.iohk.bytes.ByteString
import io.iohk.scevm.plutus.DatumEncoder
import io.iohk.scevm.plutus.DatumEncoder.EncoderOpsFromDatum
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.CombinedMerkleProof
import io.iohk.scevm.sidechain.transactions.merkletree._

object TransactionsMerkleTree {
  def createProof(
      tree: MerkleTree,
      txBatchEntry: OutgoingTransaction,
      previousOutgoingTransactionsBatch: Option[RootHash]
  ): Option[CombinedMerkleProof] = {
    val e = MerkleTreeEntry(
      txBatchEntry.txIndex,
      txBatchEntry.value,
      txBatchEntry.recipient,
      previousOutgoingTransactionsBatch
    )
    val node = DatumEncoder[MerkleTreeEntry].encode(e).toCborBytes
    MerkleTree.lookupMp(node, tree).map(mp => CombinedMerkleProof(e, mp))
  }

  def apply(
      previousOutgoingTransactionsBatch: Option[RootHash],
      transactions: NonEmptyList[OutgoingTransaction]
  ): MerkleTree = {
    val transactionsAsCbor = transactions
      .map(createNode(_, previousOutgoingTransactionsBatch))
    MerkleTree.fromNonEmptyList(transactionsAsCbor)
  }

  private def createNode(t: OutgoingTransaction, previousRootHash: Option[RootHash]): ByteString = {
    val e = MerkleTreeEntry(t.txIndex, t.value, t.recipient, previousRootHash)
    DatumEncoder[MerkleTreeEntry].encode(e).toCborBytes
  }

  def verifyProof(rootHash: RootHash)(proof: CombinedMerkleProof): Boolean = {
    val node = DatumEncoder[MerkleTreeEntry].encode(proof.transaction).toCborBytes
    MerkleTree.memberMp(node, proof.merkleProof, rootHash)
  }
}
