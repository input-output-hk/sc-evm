package io.iohk.scevm.sidechain

import cats.Applicative
import cats.Order.fromOrdering
import cats.effect.IO
import cats.syntax.all._
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA}
import io.iohk.scevm.domain._
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.testing.SidechainGenerators.committeeGenerator
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import io.janstenpickle.trace4cats.inject.Trace
import org.scalacheck.Gen
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SidechainLeaderElectionSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with IntegrationPatience {

  "should return error if the committee provider returned error" in {
    forAll(Gen.posNum[BigInt].map(Slot.apply)) { slot =>
      val electionFailure = Left(ElectionFailure.notEnoughCandidates)
      val leaderElection  = createLeaderElection(electionFailure, 1)
      val result          = leaderElection.getSlotLeader(slot).ioValue
      result shouldBe electionFailure
    }
  }

  "when the committee size is smaller than 1/2 of sidechain epoch it should follow round robin within that epoch" in {
    forAll(for {
      threshold  <- Gen.chooseNum(1, 25)
      candidates <- committeeGenerator[ECDSA](threshold).suchThat(_.size == threshold)
    } yield (candidates, threshold)) { case (candidates, committeeSize) =>
      0.until(committeeSize).foreach { index =>
        val slot = Slot(index)

        val (result, nextRoundResult) = {
          val leaderElection = createLeaderElection(
            candidates = Right(CommitteeElectionSuccess(candidates)),
            slotsInEpoch = committeeSize * 2
          )
          IO.both(leaderElection.getSlotLeader(slot), leaderElection.getSlotLeader(Slot(slot.number + committeeSize)))
            .ioValue
        }

        result.isRight shouldBe true
        result shouldBe nextRoundResult
      }
    }
  }

  "when the size of committee is equal to minimal threshold then the committee (not necessarily in the same order) should stay the same across sidechain epochs" in {
    forAll(for {
      threshold <- Gen.chooseNum(1, 25)
      candidates <-
        committeeGenerator[ECDSA](threshold)
          .map(_.map(c => c.copy(stakeDelegation = Lovelace(1))))
          .suchThat(_.size == threshold)
    } yield (threshold, candidates)) { case (threshold, candidates) =>
      val roundSize = threshold

      val (firstRoundResults, secondRoundResults) = {
        val leaderElection = createLeaderElection(
          candidates = Right(CommitteeElectionSuccess(candidates.sortBy(_.pubKey)(fromOrdering))),
          slotsInEpoch = roundSize
        )
        val firstRoundResults = 0.until(roundSize).toList.traverse { index =>
          val slot = Slot(index)
          leaderElection.getSlotLeader(slot)
        }

        val secondRoundResults = 0.until(roundSize).toList.traverse { index =>
          val slot = Slot(index + roundSize)
          leaderElection.getSlotLeader(slot)
        }
        IO.both(firstRoundResults, secondRoundResults).ioValue
      }
      firstRoundResults should contain theSameElementsAs secondRoundResults
      (firstRoundResults ++ secondRoundResults).foreach { r =>
        r.isRight shouldBe true
      }
    }
  }

  private def createLeaderElection[Schema <: AbstractSignatureScheme](
      candidates: Either[ElectionFailure, SidechainCommitteeProvider.CommitteeElectionSuccess[Schema]],
      slotsInEpoch: Int
  ) = {
    implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
    val epochDerivationStub =
      SidechainFixtures.SidechainEpochDerivationStub[IO](slotsInEpoch = slotsInEpoch, stabilityParameter = 0)
    new SidechainLeaderElection[IO, Schema](
      epochDerivationStub,
      CommitteeProviderStub(candidates)
    )
  }
}

final case class CommitteeProviderStub[F[_]: Applicative, Schema <: AbstractSignatureScheme](
    result: Either[ElectionFailure, SidechainCommitteeProvider.CommitteeElectionSuccess[Schema]]
) extends SidechainCommitteeProvider[F, Schema] {
  override def getCommittee(
      epoch: SidechainEpoch
  ): F[Either[ElectionFailure, SidechainCommitteeProvider.CommitteeElectionSuccess[Schema]]] = result.pure[F]
}
