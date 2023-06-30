package io.iohk.scevm.sidechain

import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.domain._
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.ledger.{LeaderElection, RoundRobinLeaderElection}
import io.iohk.scevm.logging.TracedLogger
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class SidechainLeaderElection[F[_]: Async: Trace, Scheme <: AbstractSignatureScheme](
    epochDerivation: SidechainEpochDerivation[F],
    committeeProvider: SidechainCommitteeProvider[F, Scheme]
) extends LeaderElection[F] {
  private val log: StructuredLogger[F] = TracedLogger(Slf4jLogger.getLogger[F])

  override def getSlotLeader(slot: Slot): F[Either[ElectionFailure, SidechainPublicKey]] = {
    val epoch = epochDerivation.getSidechainEpoch(slot)
    log.debug(show"Getting slot leader for $slot slot. Sidechain epoch is $epoch") >>
      EitherT(getCommittee(epoch)).flatMapF { case CommitteeElectionSuccess(committee) =>
        roundRobinSlotLeader(slot, committee.toVector)
      }.value
  }

  private def roundRobinSlotLeader(slot: Slot, committee: Vector[ValidLeaderCandidate[Scheme]]) =
    new RoundRobinLeaderElection[F](committee.map(pk => pk.pubKey))
      .getSlotLeader(slot)
      .flatTap { leader =>
        log.debug(show"Current elected leader: $leader")
      }

  private def getCommittee(epoch: SidechainEpoch): F[Either[ElectionFailure, CommitteeElectionSuccess[Scheme]]] =
    Trace[F]
      .span("SidechainCommitteeProvider::getCommittee")(committeeProvider.getCommittee(epoch))
}
