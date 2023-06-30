package io.iohk.scevm.sidechain.committee

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.kernel.Sync
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, Show}
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.ethereum.utils.ByteStringUtils.byteStringOrdering
import io.iohk.scevm.cardanofollower.datasource.{ValidEpochData, ValidLeaderCandidate}
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.random.RichRandomOps
import io.iohk.scevm.sidechain.{CommitteeConfig, SidechainEpoch}
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.math.Ordered.orderingToOrdered

object CommitteeElector {

  def calculateCommittee[F[_]: Sync: Trace, Scheme <: AbstractSignatureScheme](
      epoch: SidechainEpoch,
      epochData: ValidEpochData[Scheme],
      config: CommitteeConfig
  ): F[Either[CommitteeElectionFailure, CommitteeElectionSuccess[Scheme]]] = {
    implicit val log: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(this.getClass)
    val previousEpochNonce                         = epochData.previousEpochNonce
    val registeredCandidates                       = epochData.candidates
    for {
      seed      <- Applicative[F].pure((previousEpochNonce.value + epoch.number).longValue)
      _         <- log.debug(show"Seed value for committee calculation: '$seed' (sidechain epoch: $epoch)")
      committee <- CommitteeElector.electCommittee[F, Scheme](seed, registeredCandidates, config).value
    } yield committee
  }

  private def verifyCandidateNumberAboveThreshold[F[_]: Sync, Scheme <: AbstractSignatureScheme](
      registeredCandidates: Set[ValidLeaderCandidate[Scheme]],
      config: CommitteeConfig
  ): EitherT[F, CommitteeElectionFailure, Unit] =
    EitherT.cond(
      registeredCandidates.size >= config.minRegisteredCandidates,
      (),
      CommitteeElectionFailure.MinimalThresholdNotMet(registeredCandidates, config.minRegisteredCandidates)
    )

  private def verifyStakesSumAboveThreshold[F[_]: Sync, Scheme <: AbstractSignatureScheme](
      registeredCandidates: Set[ValidLeaderCandidate[Scheme]],
      config: CommitteeConfig
  ): EitherT[F, CommitteeElectionFailure, Unit] = {
    val candidatesStakeSum = registeredCandidates.map(v => v.stakeDelegation).sum
    EitherT.cond(
      candidatesStakeSum >= config.minStakeThreshold,
      (),
      CommitteeElectionFailure.NotEnoughStake(config.minStakeThreshold, candidatesStakeSum)
    )
  }

  private def verifyComitteeIsValid[F[_]: Sync, Scheme <: AbstractSignatureScheme](
      electedCommittee: Vector[ValidLeaderCandidate[Scheme]],
      registeredCandidates: Set[ValidLeaderCandidate[Scheme]]
  ): EitherT[F, CommitteeElectionFailure, NonEmptyVector[ValidLeaderCandidate[Scheme]]] =
    EitherT.fromOption[F](
      NonEmptyVector.fromVector(electedCommittee),
      CommitteeElectionFailure.EmptyCommitteeElected(registeredCandidates)
    )

  private def electCommittee[F[_]: Trace: Sync, Scheme <: AbstractSignatureScheme](
      seed: Long,
      candidates: Set[ValidLeaderCandidate[Scheme]],
      config: CommitteeConfig
  ): EitherT[F, CommitteeElectionFailure, CommitteeElectionSuccess[Scheme]] =
    for {
      _      <- verifyCandidateNumberAboveThreshold(candidates, config)
      _      <- verifyStakesSumAboveThreshold(candidates, config)
      random <- EitherT.liftF(Random.javaUtilRandom[F](new java.util.Random(seed)))
      committee <-
        EitherT.liftF(
          Trace[F].span(s"${getClass.getSimpleName}::selectMany")(
            selectMany[F, Scheme](candidates, random, config.committeeSize)
          )
        )
      validatedCommittee <- verifyComitteeIsValid(committee, candidates)
    } yield CommitteeElectionSuccess(validatedCommittee)

  private def selectMany[F[_]: Sync, Scheme <: AbstractSignatureScheme](
      candidates: Set[ValidLeaderCandidate[Scheme]],
      random: Random[F],
      committeeSize: Int
  ): F[Vector[ValidLeaderCandidate[Scheme]]] = {
    val pubKeysBytes = candidates.toVector.sortBy(_.pubKey.bytes)
    fs2.Stream
      .unfoldEval(pubKeysBytes) { state =>
        NonEmptyVector.fromVector(state).traverse { nonEmptyState =>
          random.selectWeighted(nonEmptyState)(_.stakeDelegation.value).map { selected =>
            selected -> state.filter(_.pubKey != selected.pubKey)
          }
        }
      }
      .take(committeeSize)
      .compile
      .toVector
  }

  sealed trait CommitteeElectionFailure

  object CommitteeElectionFailure {
    final case class MinimalThresholdNotMet[Scheme <: AbstractSignatureScheme](
        candidates: Set[ValidLeaderCandidate[Scheme]],
        threshold: Int
    ) extends CommitteeElectionFailure

    object MinimalThresholdNotMet {
      implicit def show[Scheme <: AbstractSignatureScheme]: Show[MinimalThresholdNotMet[Scheme]] = Show.show(a =>
        show"Minimal number of candidates is ${a.threshold}, but only got ${a.candidates.size}, observed committee is ${a.candidates}"
      )
    }

    final case class NotEnoughStake(required: Lovelace, actual: Lovelace) extends CommitteeElectionFailure

    object NotEnoughStake {
      implicit val show: Show[NotEnoughStake] = Show.show(a =>
        show"Not enough stake in the registered candidates to form the committee. Required ${a.required}, but got ${a.actual}"
      )
    }

    final case class EmptyCommitteeElected[Scheme <: AbstractSignatureScheme](
        candidates: Set[ValidLeaderCandidate[Scheme]]
    ) extends CommitteeElectionFailure

    object EmptyCommitteeElected {
      implicit def show[Scheme <: AbstractSignatureScheme]: Show[EmptyCommitteeElected[Scheme]] =
        Show.show(a => show"An empty committee was elected, candidates were ${a.candidates}")
    }

    implicit val show: Show[CommitteeElectionFailure] = Show.show {
      case err: MinimalThresholdNotMet[_] => err.show
      case err: NotEnoughStake            => err.show
      case err: EmptyCommitteeElected[_]  => err.show
    }
  }
}
