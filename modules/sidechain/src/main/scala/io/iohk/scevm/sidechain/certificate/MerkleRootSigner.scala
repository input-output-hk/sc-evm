package io.iohk.scevm.sidechain.certificate

import cats.data.NonEmptyList
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, AnySignature}
import io.iohk.scevm.plutus.DatumEncoder._
import io.iohk.scevm.plutus.{DatumCodec, DatumEncoder}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, TransactionsMerkleTree}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.Blake2bHash32

object MerkleRootSigner {

  final case class SignedMerkleRootHash[Signature <: AnySignature](rootHash: RootHash, signature: Signature)

  def sign[SignatureScheme <: AbstractSignatureScheme](
      previousOutgoingTransactionsBatchHash: Option[RootHash],
      sidechainParams: SidechainParams,
      txs: NonEmptyList[OutgoingTransaction]
  )(prvKey: SignatureScheme#PrivateKey): SignedMerkleRootHash[SignatureScheme#Signature] = {
    val currentBatchHash     = TransactionsMerkleTree(previousOutgoingTransactionsBatchHash, txs).rootHash
    val insertionMessageHash = messageHash(previousOutgoingTransactionsBatchHash, sidechainParams, currentBatchHash)
    SignedMerkleRootHash(currentBatchHash, prvKey.sign(insertionMessageHash))
  }

  def messageHash(
      previousOutgoingTransactionsBatch: Option[RootHash],
      sidechainParams: SidechainParams,
      currentTxsRootHash: RootHash
  ): ByteString = {
    val message = MerkleRootInsertionMessage(sidechainParams, currentTxsRootHash, previousOutgoingTransactionsBatch)
    val bytes   = DatumEncoder[MerkleRootInsertionMessage].encode(message).toCborBytes
    Blake2bHash32.hash(bytes).bytes
  }

  final private case class MerkleRootInsertionMessage(
      sidechainParams: SidechainParams,
      outgoingTransactionsBatch: RootHash,
      previousOutgoingTransactionsBatch: Option[RootHash]
  )

  implicit private val merkleRootInsertionMessageDatumEncoder: DatumCodec[MerkleRootInsertionMessage] =
    DatumCodec.derive
}
