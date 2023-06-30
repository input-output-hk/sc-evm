package io.iohk.scevm.sidechain.committee

import cats.Show
import cats.data.{EitherT, NonEmptyVector}
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.{ValidEpochData, ValidLeaderCandidate}
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.logging.TracedLogger
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.{CommitteeConfig, MainchainEpochDerivation, SidechainEpoch}
import io.iohk.scevm.trustlesssidechain.cardano.MainchainEpoch
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class SidechainCommitteeProviderImpl[F[_]: Sync: Trace, Scheme <: AbstractSignatureScheme](
    mainchainEpochDerivation: MainchainEpochDerivation,
    getValidEpochData: MainchainEpoch => F[Option[ValidEpochData[Scheme]]],
    config: CommitteeConfig
) extends SidechainCommitteeProvider[F, Scheme] {
  private val log: StructuredLogger[F] = TracedLogger(Slf4jLogger.getLogger[F])

  def getCommittee(epoch: SidechainEpoch): F[Either[ElectionFailure, CommitteeElectionSuccess[Scheme]]] = {
    val mainchainEpoch = mainchainEpochDerivation.getMainchainEpoch(epoch)
    Trace[F]
      .span("SidechainCommitteeProvider::getValidEpochData")(getValidEpochData(mainchainEpoch))
      .flatMap {
        case Some(validEpochData) =>
          EitherT(CommitteeElector.calculateCommittee[F, Scheme](epoch, validEpochData, config))
            .leftMap(err => ElectionFailure.requirementsNotMeet(err.show))
            .value
        case None =>
          log
            .warn(s"No data available for given mainchain epoch $mainchainEpoch")
            .as(ElectionFailure.dataNotAvailable(mainchainEpoch.number, epoch.number).asLeft)
      }
  }
}

trait SidechainCommitteeProvider[F[_], Scheme <: AbstractSignatureScheme] {
  def getCommittee(epoch: SidechainEpoch): F[Either[ElectionFailure, CommitteeElectionSuccess[Scheme]]]
}

object SidechainCommitteeProvider {

  final case class CommitteeElectionSuccess[Scheme <: AbstractSignatureScheme](
      committee: NonEmptyVector[ValidLeaderCandidate[Scheme]]
  )
  object CommitteeElectionSuccess {
    implicit def _show[Scheme <: AbstractSignatureScheme]: Show[CommitteeElectionSuccess[Scheme]] =
      cats.derived.semiauto.show
  }
}
