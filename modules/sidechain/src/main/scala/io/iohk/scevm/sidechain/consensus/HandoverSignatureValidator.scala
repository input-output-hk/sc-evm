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
import io.iohk.scevm.domain.{Address, EpochPhase, ObftHeader, Slot, TransactionLogEntry}
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.BridgeContract.{HandoverSignedEvent, HandoverSignedEventDecoder}
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.{BridgeContract, HandoverTransactionProvider, SidechainEpoch, SidechainEpochDerivation}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import org.typelevel.log4cats.{Logger, LoggerFactory, SelfAwareStructuredLogger}

class HandoverSignatureValidator[F[_]: Sync: LoggerFactory, SignatureScheme <: AbstractSignatureScheme](
    bridgeAddress: Address,
    sidechainParams: SidechainParams,
    bridgeContract: HandoverTransactionProvider.BridgeContract[EvmCall[F, *], SignatureScheme],
    epochDerivation: SidechainEpochDerivation[F],
    getCommittee: SidechainEpoch => F[Either[ElectionFailure, CommitteeElectionSuccess[SignatureScheme]]],
    committeeHandoverSigner: CommitteeHandoverSigner[F]
)(implicit signatureFromBytes: FromBytes[SignatureScheme#Signature])
    extends PostExecutionValidator[F] {

  implicit private val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def validate(
      initialState: WorldType,
      header: ObftHeader,
      result: ObftBlockExecution.BlockExecutionResult
  ): F[Either[PostExecutionValidator.PostExecutionError, Unit]] =
    if (epochDerivation.getSidechainEpochPhase(header.slotNumber) == EpochPhase.Handover) {
      val logs = result.receipts.flatMap(_.logs)
      val validatedLogs = logs.filter(BridgeContract.isHandoverSignedEvent(bridgeAddress, _)).toList match {
        case log :: Nil => validateHandoverSignedEvent(result.worldState, header.slotNumber, log)
        case Nil        => EitherT.fromEither(Left("Validator should have signed the handover")).void
        case _          => EitherT.fromEither(Left("More than one handover signature event was emitted")).void
      }
      validatedLogs.leftMap(PostExecutionError(header.hash, _)).value
    } else {
      Sync[F].pure(Right(()))
    }

  private def validateHandoverSignedEvent(
      finalState: WorldType,
      slot: Slot,
      e: TransactionLogEntry
  ): EitherT[F, String, Unit] = {

    val lastMerkleRootF: F[Option[RootHash]] = bridgeContract
      .getTransactionsMerkleRootHash(epochDerivation.getSidechainEpoch(slot))
      .flatMap {
        case Some(root) => Logger[EvmCall[F, *]].debug(show"using merkle root from current epoch $root").as(root.some)
        case None =>
          bridgeContract.getPreviousMerkleRoot.flatTap {
            case Some(previous) => Logger[EvmCall[F, *]].debug(show"Using merkle root from previous epoch $previous ")
            case None           => Logger[EvmCall[F, *]].debug(show"No previous merkle root")
          }
      }
      .run(finalState)

    for {
      event            <- decodeHandoverSignedEvent(e)
      nextCommittee    <- SignatureValidator.getCommitteePublicKeys(getCommittee)(event.epoch.next)
      currentCommittee <- SignatureValidator.getCommitteePublicKeys(getCommittee)(event.epoch)
      lastMerkleRoot   <- EitherT.liftF(lastMerkleRootF)
      _                <- EitherT(validateSignature(event, nextCommittee, currentCommittee, lastMerkleRoot))
    } yield ()
  }

  private def decodeHandoverSignedEvent(
      e: TransactionLogEntry
  ): EitherT[F, String, HandoverSignedEvent[SignatureScheme]] =
    EitherT.liftF(Sync[F].delay(HandoverSignedEventDecoder[SignatureScheme].decode(e.data)))

  private def validateSignature(
      event: BridgeContract.HandoverSignedEvent[SignatureScheme],
      nextCommittee: NonEmptyList[ValidLeaderCandidate[SignatureScheme]],
      currentCommittee: NonEmptyList[ValidLeaderCandidate[SignatureScheme]],
      lastMerkleRoot: Option[RootHash]
  ): F[Either[String, Unit]] = {
    val maybeValidLeaderCandidate = currentCommittee.find(pk => Address.fromPublicKey(pk.pubKey) == event.validator)
    maybeValidLeaderCandidate match {
      case None => SignatureValidator.addressNotValidatorError.asLeft[Unit].pure[F]
      case Some(validLeaderCandidate) =>
        for {
          message <- committeeHandoverSigner.messageHash(
                       sidechainParams,
                       nextCommittee.map(_.pubKey),
                       lastMerkleRoot,
                       event.epoch
                     )
          isSignatureValid =
            event.signature
              .asInstanceOf[AbstractSignature[SignatureScheme#PublicKey, SignatureScheme#SignatureWithoutRecovery]]
              .verify(message.bytes, validLeaderCandidate.crossChainPubKey)
          _ <- logger.debug(show"Expected handover signature message: $message")
        } yield Either.cond(isSignatureValid, (), s"Invalid signature in event $event")
    }
  }

}
