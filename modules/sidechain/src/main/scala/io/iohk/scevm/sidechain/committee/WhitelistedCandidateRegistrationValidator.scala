package io.iohk.scevm.sidechain.committee

import cats.Monad
import cats.data.{Ior, NonEmptyList}
import cats.syntax.all._
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, EdDSA}
import io.iohk.scevm.cardanofollower.datasource.{LeaderCandidate, ValidLeaderCandidate}
import io.iohk.scevm.sidechain.committee.CandidateRegistration.Inactive
import io.iohk.scevm.sidechain.committee.SingleRegistrationStatus.Invalid
import io.iohk.scevm.sidechain.committee.WhitelistedCandidateRegistrationValidator._
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

class WhitelistedCandidateRegistrationValidator[F[_]: Monad: LoggerFactory, SignatureScheme <: AbstractSignatureScheme](
    wrappedValidation: CandidateRegistrationValidator[F, SignatureScheme],
    whitelist: Set[EdDSA.PublicKey]
) extends CandidateRegistrationValidator[F, SignatureScheme] {

  implicit private val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def validate(
      candidates: List[LeaderCandidate[SignatureScheme]]
  ): F[Set[ValidLeaderCandidate[SignatureScheme]]] = {
    val (whitelistedCandidates, discardedCandidates) = candidates.partition(c => whitelist.contains(c.mainchainPubKey))

    for {
      _ <- if (discardedCandidates.nonEmpty) {
             logger.debug(
               "Some registration were discarded because they do not belong to the whitelisted SPOs:\n" +
                 discardedCandidates.map(c => show"$c").mkString(" - ")
             )
           } else Monad[F].unit
      validated <- wrappedValidation.validate(whitelistedCandidates)
    } yield validated
  }

  override def validateCandidate(
      leaderCandidate: LeaderCandidate[SignatureScheme]
  ): CandidateRegistration[SignatureScheme] =
    wrappedValidation.validateCandidate(leaderCandidate) match {
      case active @ CandidateRegistration.Active(potentiallyActive, mainchainPubKey, superseded, pending, invalid) =>
        if (whitelist.contains(mainchainPubKey)) active
        else {
          Inactive(
            mainchainPubKey,
            Ior.left(
              NonEmptyList(
                Invalid(potentiallyActive, reason),
                (superseded ++ invalid ++ pending).map(registrationStatusToInactive)
              )
            )
          )
        }
      case inactive @ CandidateRegistration.Inactive(mainchainPubKey, statuses) =>
        if (whitelist.contains(mainchainPubKey)) inactive
        else
          Inactive(
            mainchainPubKey,
            Ior.left(
              statuses
                .leftWiden[NonEmptyList[SingleRegistrationStatus[SignatureScheme]]]
                .merge
                .map(registrationStatusToInactive)
            )
          )
    }

  private def registrationStatusToInactive(status: SingleRegistrationStatus[SignatureScheme]) = status match {
    case Invalid(registrationData, reasons) =>
      Invalid(registrationData, reasons.concatNel(reason))
    case other =>
      Invalid(other.registrationData, reason)
  }

}

object WhitelistedCandidateRegistrationValidator {
  private val reason: NonEmptyList[String] = NonEmptyList.of("The SPO is not allowed to participate in this sidechain")
}
