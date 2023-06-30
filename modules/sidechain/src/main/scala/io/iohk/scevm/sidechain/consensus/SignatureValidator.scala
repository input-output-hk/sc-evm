package io.iohk.scevm.sidechain.consensus

import cats.Monad
import cats.data.{EitherT, NonEmptyList}
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess

object SignatureValidator {
  private[consensus] def getCommitteePublicKeys[F[_]: Monad, Scheme <: AbstractSignatureScheme](
      getCommittee: SidechainEpoch => F[Either[ElectionFailure, CommitteeElectionSuccess[Scheme]]]
  )(epoch: SidechainEpoch): EitherT[F, String, NonEmptyList[ValidLeaderCandidate[Scheme]]] =
    for {
      keys <- EitherT(getCommittee(epoch))
                .map(_.committee.toList)
                .leftMap(ef => show"Getting committee for epoch $epoch failed: $ef")
      nelKeys <-
        EitherT.fromOption(NonEmptyList.fromList(keys), s"Obtained committee for sidechain epoch $epoch is empty!")
    } yield nelKeys

  val addressNotValidatorError =
    "The address contained in the event does not belong to a validator in the current committee"
}
