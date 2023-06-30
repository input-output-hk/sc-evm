package io.iohk.scevm.sidechain.committee

import cats.Monad
import cats.data.{Ior, NonEmptyList, Validated}
import cats.effect.kernel.Sync
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignature, AbstractSignatureScheme}
import io.iohk.scevm.cardanofollower.datasource.{LeaderCandidate, RegistrationData, ValidLeaderCandidate}
import io.iohk.scevm.domain.MainchainPublicKey
import io.iohk.scevm.sidechain.committee.SingleRegistrationStatus.{Invalid, Pending, Superseded}
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.trustlesssidechain.{RegistrationMessage, SidechainParams}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, StructuredLogger}

import scala.math.Ordered.orderingToOrdered

trait CandidateRegistrationValidator[F[_], CrossChainScheme <: AbstractSignatureScheme] {
  def validate(candidates: List[LeaderCandidate[CrossChainScheme]]): F[Set[ValidLeaderCandidate[CrossChainScheme]]]

  def validateCandidate(leaderCandidate: LeaderCandidate[CrossChainScheme]): CandidateRegistration[CrossChainScheme]
}

class CandidateRegistrationValidatorImpl[F[_]: Sync, CrossChainScheme <: AbstractSignatureScheme](
    sidechainParams: SidechainParams,
    minimalStakeThreshold: Lovelace
) extends CandidateRegistrationValidator[F, CrossChainScheme] {
  implicit private val logger: StructuredLogger[F] = Slf4jLogger.getLogger[F]

  override def validate(
      candidates: List[LeaderCandidate[CrossChainScheme]]
  ): F[Set[ValidLeaderCandidate[CrossChainScheme]]] =
    CandidateRegistrationValidatorImpl.validateCandidates[F, CrossChainScheme](
      sidechainParams,
      candidates,
      minimalStakeThreshold
    )

  /** Validate a candidate and potentially returns what errors were encountered. It's
    * possible to have a valid candidate among the registrations but have some invalid registrations.
    *
    * @param leaderCandidate The candidate to validate
    * @return The validated candidate and potential error encountered
    */
  def validateCandidate(leaderCandidate: LeaderCandidate[CrossChainScheme]): CandidateRegistration[CrossChainScheme] =
    CandidateRegistrationValidatorImpl.validateCandidate(leaderCandidate, sidechainParams, minimalStakeThreshold)

}

object CandidateRegistrationValidatorImpl {

  private def validateCandidates[F[_]: Logger: Monad, CrossChainScheme <: AbstractSignatureScheme](
      sidechainParams: SidechainParams,
      candidates: List[LeaderCandidate[CrossChainScheme]],
      minimalStakeThreshold: Lovelace
  ): F[Set[ValidLeaderCandidate[CrossChainScheme]]] =
    candidates
      .traverseFilter(validateAndLog(_, sidechainParams, minimalStakeThreshold))
      .map(_.toSet)

  private def validateAndLog[F[_]: Logger: Monad, CrossChainScheme <: AbstractSignatureScheme](
      leaderCandidate: LeaderCandidate[CrossChainScheme],
      sidechainParams: SidechainParams,
      minimalStakeThreshold: Lovelace
  ) =
    validateCandidate(leaderCandidate, sidechainParams, minimalStakeThreshold) match {
      case CandidateRegistration.Active(reg, _, superseded, _, invalid) =>
        for {
          _ <- if (superseded.nonEmpty) {
                 Logger[F]
                   .debug(
                     show"Several active registrations ${superseded.mkString(",")}. Only ${reg.utxoInfo} will be kept as it is the latest."
                   )
               } else {
                 ().pure[F]
               }
          _ <- if (invalid.nonEmpty) {
                 Logger[F]
                   .debug(
                     show"Some registration for candidate ${leaderCandidate.mainchainPubKey} were ignored due to:\n" +
                       show"${invalid.map(_.reasons).mkString("\n - ")}"
                   )
               } else {
                 Monad[F].unit
               }
        } yield ValidLeaderCandidate(reg.sidechainPubKey, reg.crossChainPubKey, reg.stakeDelegation).some
      case CandidateRegistration.Inactive(_, inactive) =>
        Logger[F]
          .debug(dropCandidateSummary(leaderCandidate, inactive))
          .as(none[ValidLeaderCandidate[CrossChainScheme]])
    }

  private def dropCandidateSummary[CrossChainScheme <: AbstractSignatureScheme](
      leaderCandidate: LeaderCandidate[CrossChainScheme],
      inactiveAndPending: Ior[NonEmptyList[Invalid[CrossChainScheme]], NonEmptyList[Pending[CrossChainScheme]]]
  ) = {
    val mapInvalid = (invalids: NonEmptyList[Invalid[CrossChainScheme]]) =>
      invalids
        .map(i => show"\t- ${i.registrationData.utxoInfo}:${i.reasons.toList.mkString("\n\t\t - ", "\n\t\t - ", "")}")
        .toList
        .mkString("Some registrations were invalid due to:\n", "\n", "")
    val mapPending = (pending: NonEmptyList[Pending[CrossChainScheme]]) =>
      pending
        .map(p => show"\t- ${p.registrationData.utxoInfo}")
        .toList
        .mkString("Some registrations are still pending:\n", "\n", "")

    val inactiveAndPendingSummary = inactiveAndPending
      .bimap(mapInvalid, mapPending)
      .fold(identity, identity, (a, b) => s"$a\n$b")
    show"""Dropping candidate ${leaderCandidate.mainchainPubKey}
          |$inactiveAndPendingSummary
          |""".stripMargin
  }

  def validateCandidate[CrossChainScheme <: AbstractSignatureScheme](
      candidate: LeaderCandidate[CrossChainScheme],
      sidechainParams: SidechainParams,
      minimalStakeThreshold: Lovelace
  ): CandidateRegistration[CrossChainScheme] = {
    val parts = candidate.registrations
      .nonEmptyPartition { registration =>
        validateRegistration(candidate.mainchainPubKey, registration, sidechainParams, minimalStakeThreshold)
      }
    calculateActiveRegistration(candidate.mainchainPubKey, parts)
  }

  private def validateRegistration[CrossChainScheme <: AbstractSignatureScheme](
      candidate: MainchainPublicKey,
      registrationData: RegistrationData[CrossChainScheme],
      sidechainParams: SidechainParams,
      minimalStakeThreshold: Lovelace
  ): Either[Invalid[CrossChainScheme], RegistrationData[CrossChainScheme]] = {
    val serialized = RegistrationMessage.createEncoded(
      registrationData.sidechainPubKey,
      registrationData.consumedInput,
      sidechainParams
    )
    val hashed = Blake2bHash32.hash(serialized)
    (
      mainchainSignatureValidation(candidate, registrationData, serialized),
      sidechainSignatureValidation(registrationData, hashed),
      crossChainSignatureValidation(registrationData, hashed),
      stakeValidation(registrationData, minimalStakeThreshold),
      txInputValidation(registrationData)
    )
      .mapN((_, _, _, _, _) => registrationData)
      .leftMap(reasons => Invalid(registrationData, reasons))
      .toEither
  }

  private def stakeValidation[CrossChainScheme <: AbstractSignatureScheme](
      registrationData: RegistrationData[CrossChainScheme],
      minimalStakeThreshold: Lovelace
  ) =
    Validated.condNel(
      registrationData.stakeDelegation >= minimalStakeThreshold,
      registrationData,
      show"Insufficient candidate stake: ${registrationData.stakeDelegation}, required: $minimalStakeThreshold"
    )

  private def mainchainSignatureValidation[CrossChainScheme <: AbstractSignatureScheme](
      candidate: MainchainPublicKey,
      registrationData: RegistrationData[CrossChainScheme],
      serialized: ByteString
  ) =
    Validated.condNel(
      registrationData.mainchainSignature.verify(serialized, candidate),
      registrationData,
      show"Invalid mainchain signature for utxo ${registrationData.utxoInfo.utxoId}"
    )

  private def sidechainSignatureValidation[CrossChainScheme <: AbstractSignatureScheme](
      registrationData: RegistrationData[CrossChainScheme],
      hashed: Blake2bHash32
  ) =
    Validated.condNel(
      registrationData.sidechainSignature.verify(hashed.bytes, registrationData.sidechainPubKey),
      registrationData,
      show"Invalid sidechain signature for utxo ${registrationData.utxoInfo.utxoId}"
    )

  private def crossChainSignatureValidation[CrossChainScheme <: AbstractSignatureScheme](
      registrationData: RegistrationData[CrossChainScheme],
      hashed: Blake2bHash32
  ) =
    Validated.condNel(
      (registrationData.crossChainSignature
        .asInstanceOf[AbstractSignature[AbstractSignatureScheme#PublicKey, CrossChainScheme#SignatureWithoutRecovery]])
        .verify(hashed.bytes, registrationData.crossChainPubKey),
      registrationData,
      show"Invalid cross-chain signature for utxo ${registrationData.utxoInfo.utxoId}"
    )

  private def txInputValidation[CrossChainScheme <: AbstractSignatureScheme](
      registrationData: RegistrationData[CrossChainScheme]
  ) =
    Validated.condNel(
      registrationData.txInputs.contains(registrationData.consumedInput),
      registrationData,
      show"Registration transaction ${registrationData.utxoInfo.utxoId} hasn't consumed utxo indicated in the datum"
    )

  // A SPO can create several registration utxos as the plutus script cannot check that
  // We have to make sure that we use the same registration every time. So we are always taking the
  // latest registration by ordering them by their (block number, tx index, utxo index)
  private def calculateActiveRegistration[CrossChainScheme <: AbstractSignatureScheme](
      candidate: MainchainPublicKey,
      registrations: Ior[NonEmptyList[Invalid[CrossChainScheme]], NonEmptyList[RegistrationData[CrossChainScheme]]]
  ): CandidateRegistration[CrossChainScheme] =
    registrations.map(separatePendingAndStable).invertIor match {
      case Ior.Left(invalidAndPending) => CandidateRegistration.Inactive(candidate, invalidAndPending)
      case Ior.Right((superseded, last)) =>
        CandidateRegistration.Active(last, candidate, superseded, Nil, Nil)
      case Ior.Both(invalidAndPending, (superseded, last)) =>
        CandidateRegistration.Active(
          last,
          candidate,
          superseded,
          invalidAndPending.right.map(_.toList).getOrElse(Nil),
          invalidAndPending.left.map(_.toList).getOrElse(Nil)
        )
    }

  implicit final private class NestedIorOps[A, B, C](ior: Ior[A, Ior[B, C]]) {
    def invertIor: Ior[Ior[A, B], C] = ior match {
      case Ior.Left(a)                 => Ior.left(Ior.left(a))
      case Ior.Right(bc)               => bc.leftMap(Ior.right)
      case Ior.Both(a, Ior.Left(b))    => Ior.Left(Ior.Both(a, b))
      case Ior.Both(a, Ior.Both(b, c)) => Ior.Both(Ior.Both(a, b), c)
      case Ior.Both(a, Ior.Right(c))   => Ior.Both(Ior.Left(a), c)
    }
  }

  private def separatePendingAndStable[CrossChainScheme <: AbstractSignatureScheme](
      spoRegistrations: NonEmptyList[RegistrationData[CrossChainScheme]]
  ): Ior[NonEmptyList[
    Pending[CrossChainScheme]
  ], (List[Superseded[CrossChainScheme]], RegistrationData[CrossChainScheme])] =
    spoRegistrations
      .nonEmptyPartition(r => Either.cond(!r.isPending, r, Pending(r)))
      .map { stable =>
        val latestRegistration      = stable.maximumBy(v => utxoOrdering(v.utxoInfo))
        val supersededRegistrations = stable.filterNot(_ == latestRegistration).map(s => Superseded(s))
        (supersededRegistrations, latestRegistration)
      }

  private val utxoOrdering = (utxoInfo: UtxoInfo) =>
    (utxoInfo.blockNumber.value, utxoInfo.txIndexWithinBlock, utxoInfo.utxoId.index)

}
