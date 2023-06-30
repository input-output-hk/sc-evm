package io.iohk.scevm.sidechain.committee

import cats.effect.IO
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.cardanofollower.datasource.{ValidEpochData, ValidLeaderCandidate}
import io.iohk.scevm.sidechain.committee.CommitteeElector.CommitteeElectionFailure
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.{CommitteeConfig, SidechainEpoch}
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace.numeric._
import io.iohk.scevm.trustlesssidechain.cardano.{EpochNonce, Lovelace}
import io.janstenpickle.trace4cats.inject.Trace
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.security.SecureRandom

class CommitteeElectorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IOSupport
    with IntegrationPatience
    with EitherValues
    with ScalaCheckPropertyChecks {
  implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  "distribution of candidates should match their stake" in {
    val C          = 1000
    val S          = 100
    val bigStake   = Lovelace(2)
    val smallStake = Lovelace(1)
    val random     = new SecureRandom(Array.empty[Byte])
    val candidates = (1 to C).map { i =>
      val stake                 = if (i % 2 == 0) bigStake else smallStake
      val (_, pubKey)           = ECDSA.generateKeyPair(random)
      val (_, crossChainPubKey) = ECDSA.generateKeyPair(random)
      ValidLeaderCandidate(pubKey, crossChainPubKey, stake)
    }.toSet

    val selected = CommitteeElector
      .calculateCommittee[IO, ECDSA](
        SidechainEpoch(0),
        ValidEpochData(EpochNonce(S), candidates),
        CommitteeConfig(1, S, Lovelace(0))
      )
      .ioValue
      .value
    val biggerStakeCount         = selected.committee.toVector.count(_.stakeDelegation.value == 2)
    val expectedBiggerStakeCount = S * 2 / 3
    val tolerance                = S / 5
    biggerStakeCount shouldBe expectedBiggerStakeCount +- tolerance
  }

  "should return error if there are less candidates than threshold" in {
    forAll(for {
      threshold      <- Gen.choose[Int](1, 1000)
      candidatesSize <- Gen.posNum[Int].suchThat(_ < threshold)
      candidates     <- candidateGenerator(candidatesSize)
    } yield (candidates, threshold)) { case (candidates, threshold) =>
      val provider = createCommitteeProvider(
        candidates = candidates,
        threshold = threshold,
        committeeSize = 1,
        epochNonce = 123L
      )
      val value1 = provider.apply(SidechainEpoch(1)).ioValue

      value1 shouldBe Left(CommitteeElectionFailure.MinimalThresholdNotMet(candidates, threshold))
    }
  }

  "should return error if there is less stake in total than the minimal threshold" in {
    forAll(for {
      threshold      <- Gen.choose[Int](1, 100)
      candidatesSize <- Gen.choose[Int](threshold, threshold * 10)
      candidates     <- candidateGenerator(candidatesSize)
    } yield (candidates, threshold)) { case (candidates, threshold) =>
      val stakeThreshold = candidates.map(_.stakeDelegation).sum + Lovelace(1)
      val provider = createCommitteeProvider(
        candidates = candidates,
        threshold = threshold,
        committeeSize = 1,
        epochNonce = 123L,
        stakeThreshold = stakeThreshold
      )
      val value1 = provider.apply(SidechainEpoch(1)).ioValue

      value1 shouldBe Left(
        CommitteeElectionFailure.NotEnoughStake(stakeThreshold, candidates.map(_.stakeDelegation).sum)
      )
    }
  }

  "should return committee for various correct input parameters" in {
    forAll(for {
      threshold      <- Gen.choose[Int](1, 100)
      candidatesSize <- Gen.choose[Int](threshold, threshold * 10)
      committeeSize  <- Gen.choose[Int](1, threshold)
      candidates     <- candidateGenerator(candidatesSize)
    } yield (candidates, threshold, committeeSize)) { case (candidates, threshold, committeeSize) =>
      val provider = createCommitteeProvider(
        candidates = candidates,
        threshold = threshold,
        committeeSize = committeeSize,
        epochNonce = 123L
      )
      val result = provider.apply(SidechainEpoch(1)).ioValue
      result match {
        case Left(error) => fail(s"Expected right but got $error")
        case Right(_)    => succeed
      }
    }
  }

  "the committee should change every sidechain epoch" in {
    val threshold     = 25
    val candidates    = candidateGenerator(threshold * 40).sample.get
    val committeeSize = threshold

    val provider = createCommitteeProvider(
      candidates = candidates,
      threshold = threshold,
      committeeSize = committeeSize,
      epochNonce = 123L
    )
    val results = (0 until committeeSize).map(i => provider.apply(SidechainEpoch(i)).ioValue)
    results.map(_.value).toSet should have size committeeSize
  }

  "mainchain randomness should influence the results of electing the committee" in {
    val threshold     = 25
    val candidates    = candidateGenerator(threshold * 40).sample.get
    val committeeSize = threshold
    val provider1 = createCommitteeProvider(
      candidates = candidates,
      threshold = threshold,
      committeeSize = committeeSize,
      epochNonce = 123L
    )
    val firstRoundResults = provider1.apply(SidechainEpoch(1)).ioValue

    val provider2 = createCommitteeProvider(
      candidates = candidates,
      threshold = threshold,
      committeeSize = committeeSize,
      epochNonce = 124L
    )
    val secondRoundResults = provider2.apply(SidechainEpoch(1)).ioValue

    firstRoundResults.value.committee.toVector should not contain theSameElementsAs(
      secondRoundResults.value.committee.toVector
    )
  }

  "given the same set of inputs the election should yield the same results every time" in {
    val threshold     = 25
    val candidates    = candidateGenerator(threshold * 40).sample.get
    val committeeSize = threshold

    val provider1 = createCommitteeProvider(
      candidates = candidates,
      threshold = threshold,
      committeeSize = committeeSize,
      epochNonce = 123L
    )
    val firstRoundResults = provider1.apply(SidechainEpoch(1)).ioValue

    val provider2 = createCommitteeProvider(
      candidates = candidates,
      threshold = threshold,
      committeeSize = committeeSize,
      epochNonce = 123L
    )
    val secondRoundResults = provider2.apply(SidechainEpoch(1)).ioValue

    firstRoundResults.value.committee shouldBe secondRoundResults.value.committee
  }

  private def createCommitteeProvider(
      candidates: Set[ValidLeaderCandidate[ECDSA]],
      threshold: Int,
      committeeSize: Int,
      epochNonce: Long,
      stakeThreshold: Lovelace = Lovelace(0)
  ) = {
    implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
    CommitteeElector.calculateCommittee[IO, ECDSA](
      _,
      ValidEpochData(EpochNonce(epochNonce), candidates),
      CommitteeConfig(threshold, committeeSize, stakeThreshold)
    )
  }

  private def candidateGenerator(size: Int) =
    SidechainGenerators.committeeGenerator[ECDSA](size).map(_.toVector.toSet)
}
