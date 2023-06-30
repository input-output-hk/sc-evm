package io.iohk.scevm.sidechain

import cats.Monad
import cats.data.{EitherT, NonEmptyList}
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.domain.{SidechainPublicKey, UInt256}
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.ledger.NonceProvider
import io.iohk.scevm.sidechain.certificate.CheckpointSigner.SignedCheckpoint
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner
import io.iohk.scevm.sidechain.certificate.MerkleRootSigner.{SignedMerkleRootHash, sign}
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, merkletree}
import io.iohk.scevm.trustlesssidechain.SidechainParams

trait HandoverTransactionProvider[F[_], SignatureScheme <: AbstractSignatureScheme] {

  def getSignTx(
      epoch: SidechainEpoch,
      world: WorldType,
      crossChainPrivateKey: SignatureScheme#PrivateKey
  ): F[Either[String, SystemTransaction]]
}

object HandoverTransactionProvider {
  trait BridgeContract[F[_], SignatureScheme <: AbstractSignatureScheme] {
    def createSignTransaction(
        handoverSignature: SignatureScheme#Signature,
        checkpoint: Option[SignedCheckpoint],
        txsBatchMRH: Option[SignedMerkleRootHash[SignatureScheme#Signature]]
    ): F[SystemTransaction]

    def getTransactionsInBatch(batch: UInt256): F[Seq[OutgoingTransaction]]

    def getPreviousMerkleRoot: F[Option[RootHash]]

    def getTransactionsMerkleRootHash(
        epoch: SidechainEpoch
    ): F[Option[merkletree.RootHash]]
  }
}

class HandoverTransactionProviderImpl[F[_]: Monad, SignatureScheme <: AbstractSignatureScheme](
    nonceProvider: NonceProvider[F],
    committeeProvider: SidechainCommitteeProvider[F, SignatureScheme],
    bridgeContract: HandoverTransactionProvider.BridgeContract[EvmCall[F, *], SignatureScheme],
    params: SidechainParams,
    committeeHandoverSigner: CommitteeHandoverSigner[F]
) extends HandoverTransactionProvider[F, SignatureScheme] {

  // This provider returns a transaction each time it's called within handover phase.
  // It will be invoked several times in one handover phase, but signatures submission is idempotent,
  // so it's safe to invoke it several times.
  // This is the simplest, but not effective way of trying to achieve at-least-once invocation.
  // Possible chain reorganizations make any form of caching difficult.
  def getSignTx(
      epoch: SidechainEpoch,
      world: WorldType,
      crossChainPrivateKey: SignatureScheme#PrivateKey
  ): F[Either[String, SystemTransaction]] = (for {
    nextCommittee <- EitherT(getCommittee(epoch.next))
    res           <- EitherT.right[String](makeTransaction(nextCommittee, epoch, world, crossChainPrivateKey))
  } yield res).value

  private def makeTransaction(
      nextCommittee: NonEmptyList[SidechainPublicKey],
      epoch: SidechainEpoch,
      world: WorldType,
      crossChainPrivateKey: SignatureScheme#PrivateKey
  ): F[SystemTransaction] =
    for {
      outgoingTxs         <- bridgeContract.getTransactionsInBatch(epoch.number).run(world)
      lastMerkleRoot      <- bridgeContract.getPreviousMerkleRoot.run(world)
      txsBatchMRHSignature = signTransactions(outgoingTxs, lastMerkleRoot, params, crossChainPrivateKey)
      handoverSignature <-
        committeeHandoverSigner.sign(
          params,
          nextCommittee,
          txsBatchMRHSignature.map(_.rootHash).orElse(lastMerkleRoot),
          epoch
        )(crossChainPrivateKey)
      tx <- bridgeContract
              .createSignTransaction(handoverSignature, checkpoint = None, txsBatchMRH = txsBatchMRHSignature)
              .run(world)
    } yield tx

  private def signTransactions(
      transactions: Seq[OutgoingTransaction],
      previousOutgoingTransactionsBatchHash: Option[RootHash],
      sidechainParams: SidechainParams,
      crossChainPrivateKey: SignatureScheme#PrivateKey
  ): Option[SignedMerkleRootHash[SignatureScheme#Signature]] =
    NonEmptyList.fromList(transactions.toList).map { txs =>
      sign(previousOutgoingTransactionsBatchHash, sidechainParams, txs)(crossChainPrivateKey)
    }

  private def getCommittee(epoch: SidechainEpoch): F[Either[String, NonEmptyList[SidechainPublicKey]]] =
    EitherT(committeeProvider.getCommittee(epoch))
      .map(result => result.committee.map(_.pubKey).toNonEmptyList)
      .leftMap(err => s"Couldn't get committee for epoch $epoch, because of '$err'")
      .value
}
