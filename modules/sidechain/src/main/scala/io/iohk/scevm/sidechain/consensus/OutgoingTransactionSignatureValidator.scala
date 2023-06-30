package io.iohk.scevm.sidechain.consensus

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.bytes.FromBytes
import io.iohk.ethereum.crypto.{AbstractSignature, AbstractSignatureScheme}
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.consensus.pos.ObftBlockExecution
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.domain.{Address, EpochPhase, ObftHeader, TransactionLogEntry}
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.BridgeContract.OutgoingTransactionsSignedEventDecoder
import io.iohk.scevm.sidechain.certificate.MerkleRootSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, TransactionsMerkleTree}
import io.iohk.scevm.sidechain.{BridgeContract, HandoverTransactionProvider, SidechainEpoch, SidechainEpochDerivation}
import io.iohk.scevm.trustlesssidechain.SidechainParams

class OutgoingTransactionSignatureValidator[F[_]: Sync, SignatureScheme <: AbstractSignatureScheme](
    bridgeAddress: Address,
    sidechainParams: SidechainParams,
    bridgeContract: HandoverTransactionProvider.BridgeContract[EvmCall[F, *], SignatureScheme],
    epochDerivation: SidechainEpochDerivation[F],
    getCommittee: SidechainEpoch => F[Either[ElectionFailure, CommitteeElectionSuccess[SignatureScheme]]]
)(implicit signatureFromBytes: FromBytes[SignatureScheme#Signature])
    extends PostExecutionValidator[F] {

  override def validate(
      initialState: WorldType,
      header: ObftHeader,
      result: ObftBlockExecution.BlockExecutionResult
  ): F[Either[PostExecutionValidator.PostExecutionError, Unit]] =
    if (epochDerivation.getSidechainEpochPhase(header.slotNumber) == EpochPhase.Handover) {
      val logs = result.receipts.flatMap(_.logs)
      val validatedLogs = logs.filter(BridgeContract.isOutgoingTransactionsSigned(bridgeAddress, _)).toList match {
        case log :: Nil => validateOutgoingTransactionSignedEvent(log, initialState, header)
        case Nil        => validateNoOutgoingTransaction(initialState, header)
        case _          => EitherT.fromEither(Left("More than one outgoing transaction signature was emitted")).void
      }
      validatedLogs.leftMap(PostExecutionError(header.hash, _)).value
    } else {
      Sync[F].pure(Right(()))
    }

  private def validateNoOutgoingTransaction(initialState: WorldType, header: ObftHeader): EitherT[F, String, Unit] =
    EitherT {
      val epoch = epochDerivation.getSidechainEpoch(header.slotNumber)
      bridgeContract.getTransactionsInBatch(epoch.number).run(initialState).map { transactions =>
        Either
          .cond(
            transactions.isEmpty,
            (),
            show"No signature was emitted but there are outgoing transaction for epoch $epoch"
          )

      }
    }

  def validateOutgoingTransactionSignedEvent(
      e: TransactionLogEntry,
      initialState: WorldType,
      header: ObftHeader
  ): EitherT[F, String, Unit] =
    for {
      event                <- EitherT.liftF(Sync[F].delay(OutgoingTransactionsSignedEventDecoder[SignatureScheme].decode(e.data)))
      epoch                 = epochDerivation.getSidechainEpoch(header.slotNumber)
      outgoingTransactions <- EitherT.liftF(bridgeContract.getTransactionsInBatch(epoch.number).run(initialState))
      previousRootHash     <- EitherT.liftF(bridgeContract.getPreviousMerkleRoot.run(initialState))
      rootHash             <- EitherT.fromEither(validateMerkleRoot(event, outgoingTransactions.toList, previousRootHash))
      currentCommittee     <- SignatureValidator.getCommitteePublicKeys(getCommittee)(epoch)
      _                    <- EitherT.fromEither(validateSignature(event, previousRootHash, rootHash, currentCommittee))
    } yield ()

  private def validateMerkleRoot(
      event: BridgeContract.OutgoingTransactionsSignedEvent[SignatureScheme],
      transactions: List[OutgoingTransaction],
      previousOutgoingTransactionsBatchHash: Option[RootHash]
  ): Either[String, RootHash] =
    for {
      nelTransactions <-
        NonEmptyList.fromList(transactions).toRight(show"No outgoing transactions exists but event $event was emitted.")
      expectedMerkleRoot = TransactionsMerkleTree(previousOutgoingTransactionsBatchHash, nelTransactions).rootHash
      rootHash <- Either.cond(
                    expectedMerkleRoot == event.merkleRootHash,
                    expectedMerkleRoot,
                    show"Unexpected merkle root in $event. Expected $expectedMerkleRoot"
                  )
    } yield rootHash

  private def validateSignature(
      event: BridgeContract.OutgoingTransactionsSignedEvent[SignatureScheme],
      previousRootHash: Option[RootHash],
      currentTxsRootHash: RootHash,
      currentCommittee: NonEmptyList[ValidLeaderCandidate[SignatureScheme]]
  ): Either[String, Unit] = {
    val messageHash  = MerkleRootSigner.messageHash(previousRootHash, sidechainParams, currentTxsRootHash)
    val validatorKey = currentCommittee.find(key => Address.fromPublicKey(key.pubKey) == event.validator)
    validatorKey match {
      case None => Left(SignatureValidator.addressNotValidatorError)
      case Some(pubKey) =>
        Either.cond(
          event.signature
            .asInstanceOf[AbstractSignature[SignatureScheme#PublicKey, SignatureScheme#SignatureWithoutRecovery]]
            .verify(messageHash, pubKey.crossChainPubKey),
          (),
          s"Invalid signature in event $event"
        )
    }
  }
}
