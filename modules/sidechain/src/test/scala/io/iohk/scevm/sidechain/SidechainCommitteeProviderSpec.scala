package io.iohk.scevm.sidechain

import cats.effect.IO
import cats.syntax.all._
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.committee.{CommitteeElector, SidechainCommitteeProviderImpl, ValidEpochDataProviderStub}
import io.iohk.scevm.testing.CryptoGenerators.ecdsaPublicKeyGen
import io.iohk.scevm.testing.{Generators, IOSupport}
import io.iohk.scevm.trustlesssidechain.cardano.{EpochNonce, Lovelace, MainchainEpoch}
import io.iohk.scevm.utils.SystemTime
import io.janstenpickle.trace4cats.inject.Trace
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SidechainCommitteeProviderSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with EitherValues
    with IntegrationPatience {

  "should return error if there is less candidates than threshold" in {
    forAll(for {
      threshold      <- Gen.posNum[Int].suchThat(_ >= 1)
      candidatesSize <- Gen.chooseNum(0, threshold - 1)
      committeeSize  <- Gen.chooseNum(1, threshold)
      candidates     <- candidateGenerator(candidatesSize)
    } yield (candidates, threshold, committeeSize)) { case (candidates, threshold, committeeSize) =>
      val provider = createCommitteeProvider(
        candidates = Some(candidates),
        threshold = threshold,
        committeeSize = committeeSize,
        slotsInEpoch = 5,
        epochNonce = 123L
      )
      val value1 = provider.getCommittee(SidechainEpoch(1)).ioValue

      val expected =
        ElectionFailure.requirementsNotMeet(
          CommitteeElector.CommitteeElectionFailure.MinimalThresholdNotMet(candidates, threshold).show
        )
      value1 shouldBe Left(expected)
    }
  }

  "should return committee for various correct input parameters" in {
    forAll(for {
      threshold      <- Gen.posNum[Int]
      candidatesSize <- Gen.choose[Int](threshold, threshold * 10)
      committeeSize  <- Gen.choose[Int](1, threshold)
      candidates     <- candidateGenerator(candidatesSize)
    } yield (candidates, threshold, committeeSize)) { case (candidates, threshold, committeeSize) =>
      val provider = createCommitteeProvider(
        candidates = Some(candidates),
        threshold = threshold,
        committeeSize = committeeSize,
        slotsInEpoch = 5,
        epochNonce = 123L
      )
      val result = provider.getCommittee(SidechainEpoch(1)).ioValue
      result match {
        case Left(error) => fail(s"Expected right but got $error")
        case Right(_)    => succeed
      }
    }
  }

  "the committee should change every sidechain epoch" in {
    val threshold     = 25
    val candidates    = candidateGenerator(threshold * 100).sample.get
    val committeeSize = threshold

    val provider = createCommitteeProvider(
      candidates = Some(candidates),
      threshold = threshold,
      committeeSize = committeeSize,
      slotsInEpoch = committeeSize,
      epochNonce = 123L
    )
    val results = (0 until committeeSize).map(i => provider.getCommittee(SidechainEpoch(i)).ioValue)
    results.map(_.value).toSet should have size committeeSize
  }

  "mainchain randomness should influence the results of electing the committee" in {
    val threshold     = 25
    val candidates    = candidateGenerator(threshold * 100).sample.get
    val committeeSize = threshold
    val provider1 = createCommitteeProvider(
      candidates = Some(candidates),
      threshold = threshold,
      committeeSize = committeeSize,
      slotsInEpoch = committeeSize,
      epochNonce = 123L
    )
    val firstRoundResults = provider1.getCommittee(SidechainEpoch(1)).ioValue

    val provider2 = createCommitteeProvider(
      candidates = Some(candidates),
      threshold = threshold,
      committeeSize = committeeSize,
      slotsInEpoch = committeeSize,
      epochNonce = 124L
    )
    val secondRoundResults = provider2.getCommittee(SidechainEpoch(1)).ioValue

    firstRoundResults.value.committee.toVector should not contain theSameElementsAs(
      secondRoundResults.value.committee.toVector
    )
  }

  "given the same set of inputs the election should yield the same results every time" in {
    val threshold     = 25
    val candidates    = candidateGenerator(threshold * 100).sample.get
    val committeeSize = threshold

    val provider1 = createCommitteeProvider(
      candidates = Some(candidates),
      threshold = threshold,
      committeeSize = committeeSize,
      slotsInEpoch = committeeSize,
      epochNonce = 123L
    )
    val firstRoundResults = provider1.getCommittee(SidechainEpoch(1)).ioValue

    val provider2 = createCommitteeProvider(
      candidates = Some(candidates),
      threshold = threshold,
      committeeSize = committeeSize,
      slotsInEpoch = committeeSize,
      epochNonce = 123L
    )
    val secondRoundResults = provider2.getCommittee(SidechainEpoch(1)).ioValue

    firstRoundResults.value.committee shouldBe secondRoundResults.value.committee
  }

  "should return error if there is no data for current epoch" in {
    forAll(for {
      threshold     <- Gen.posNum[Int].suchThat(_ >= 1)
      committeeSize <- Gen.chooseNum(1, threshold)
    } yield (threshold, committeeSize)) { case (threshold, committeeSize) =>
      val provider = createCommitteeProvider(
        candidates = None,
        threshold = threshold,
        committeeSize = committeeSize,
        slotsInEpoch = 5,
        epochNonce = 123L
      )
      val result = provider.getCommittee(SidechainEpoch(1)).ioValue

      result shouldBe Left(ElectionFailure.dataNotAvailable(0, 1))
    }
  }

  private def createCommitteeProviderF(
      candidates: Option[Set[ValidLeaderCandidate[ECDSA]]],
      threshold: Int,
      committeeSize: Int,
      slotsInEpoch: Int,
      epochNonce: Long,
      sidechainEpochInMainchain: Int = 100,
      slotDurationSeconds: Long = 20
  ) =
    for {
      systemTime  <- SystemTime.liveF[IO]
      currentTime <- systemTime.realTime()
      dsStub =
        new ValidEpochDataProviderStub[IO, ECDSA](candidates, Map(MainchainEpoch(0) -> EpochNonce(epochNonce)))
      implicit0(trace: Trace[IO]) = Trace.Implicits.noop[IO]
    } yield new SidechainCommitteeProviderImpl[IO, ECDSA](
      SidechainFixtures.MainchainEpochDerivationStub(
        slotsInEpoch = slotsInEpoch,
        genesisTime = currentTime,
        sidechainEpochInMainchain = sidechainEpochInMainchain,
        slotDurationSeconds = slotDurationSeconds
      ),
      dsStub.getValidEpochData,
      CommitteeConfig(threshold, committeeSize, Lovelace(0))
    )

  private def createCommitteeProvider(
      candidates: Option[Set[ValidLeaderCandidate[ECDSA]]],
      threshold: Int,
      committeeSize: Int,
      slotsInEpoch: Int,
      epochNonce: Long
  ) =
    createCommitteeProviderF(candidates, threshold, committeeSize, slotsInEpoch, epochNonce).unsafeRunSync()

  private val leaderCandidateGen =
    for {
      pubKey           <- ecdsaPublicKeyGen
      crossChainPubKey <- ecdsaPublicKeyGen
      amount           <- Gen.posNum[Long]
    } yield ValidLeaderCandidate(pubKey, crossChainPubKey, Lovelace(amount))

  private def candidateGenerator(size: Int) =
    Generators.genSetOfN[ValidLeaderCandidate[ECDSA]](size, leaderCandidateGen)
}
