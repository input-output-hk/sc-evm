package io.iohk.scevm.sync.networkstable

import cats.data.NonEmptyList
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Outcome}
import fs2.concurrent.{Signal, SignallingRef}
import io.iohk.scevm.domain.{BlockNumber, ObftHeader}
import io.iohk.scevm.network.Generators.peerIdGen
import io.iohk.scevm.network.domain.StableHeaderScore
import io.iohk.scevm.network.{Generators, PeerId, PeerWithInfo}
import io.iohk.scevm.sync.networkstable.NetworkStableHeaderResult.{
  NoAvailablePeer,
  StableHeaderFound,
  StableHeaderNotFound
}
import io.iohk.scevm.testing.BlockGenerators
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class NetworkStableHeaderResolverSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  val RETRY_COUNT                 = 2
  val RETRY_DELAY: FiniteDuration = 1.seconds
  val MIN_DENSITY_THRESHOLD       = 0.2

  val EPSILON = 0.1

  val TWO_THIRD: Double = 2.0 / 3
  val HIGH_SCORE        = TWO_THIRD
  val MID_SCORE: Double = TWO_THIRD - EPSILON
  val LOW_SCORE: Double = MIN_DENSITY_THRESHOLD - EPSILON

  val HIGH_BLOCK_NUMBER: BlockNumber = BlockNumber(1000)
  val MID_BLOCK_NUMBER: BlockNumber  = BlockNumber(950)
  val LOW_BLOCK_NUMBER: BlockNumber  = BlockNumber(900)

  "HighDensityResolver" should "return all the peers corresponding to the highest stable header with a score above or equals to 2/3" in {
    val score = HIGH_SCORE
    val highNumberStableHeader: ObftHeader =
      BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = HIGH_BLOCK_NUMBER)
    val lowNumberStableHeader: ObftHeader =
      BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = LOW_BLOCK_NUMBER)

    val peerWithInfoLowNumber: PeerWithInfo   = generatePeerWithInfo(lowNumberStableHeader, score)
    val peerWithInfoHighNumber1: PeerWithInfo = generatePeerWithInfo(highNumberStableHeader, score)
    val peerWithInfoHighNumber2: PeerWithInfo = generatePeerWithInfo(highNumberStableHeader, score)

    val peersMap = getPeersMap(peerWithInfoLowNumber, peerWithInfoHighNumber1, peerWithInfoHighNumber2)
    val resolver = NetworkStableHeaderResolver.highDensity[IO]

    val test =
      SignallingRef[IO, Map[PeerId, PeerWithInfo]](peersMap).flatMap(peersWithInfo => resolver.resolve(peersWithInfo))

    val result = test.unsafeRunSync()
    result shouldBe StableHeaderFound(
      highNumberStableHeader,
      HIGH_SCORE,
      NonEmptyList.of(peerWithInfoHighNumber1.peer.id, peerWithInfoHighNumber2.peer.id)
    )
  }

  it should "return an error when no peers are connected" in {
    checkNoPeerResult(NetworkStableHeaderResolver.highDensity[IO])
  }

  it should "answer no stable for a peer with a score under minimum density threshold 2/3" in {
    val score                      = LOW_SCORE
    val stableHeader: ObftHeader   = BlockGenerators.obftBlockHeaderGen.sample.get
    val peerWithInfo: PeerWithInfo = generatePeerWithInfo(stableHeader, score)

    val peersMap = getPeersMap(peerWithInfo)
    val resolver = NetworkStableHeaderResolver.highDensity[IO]

    val test =
      SignallingRef[IO, Map[PeerId, PeerWithInfo]](peersMap).flatMap(peersWithInfo => resolver.resolve(peersWithInfo))

    val result = test.unsafeRunSync()
    result shouldBe StableHeaderNotFound(TWO_THIRD)
  }

  "LowDensityResolver" should "return all the peers corresponding to the highest score above the minimum threshold" in {
    // 3 peers
    // 1 with low score, high block number: should be discarded, block number is not a primary criteria
    // 2 with high score but different block number: the highest (score, block number) should be considered
    val highNumberStableHeader: ObftHeader =
      BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = HIGH_BLOCK_NUMBER)
    val midNumberStableHeader: ObftHeader =
      BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = MID_BLOCK_NUMBER)
    val lowNumberStableHeader: ObftHeader =
      BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = LOW_BLOCK_NUMBER)

    val pwiLowScoreHighBlockNumber: PeerWithInfo = generatePeerWithInfo(highNumberStableHeader, LOW_SCORE)
    val pwiMidScoreLowBlockNumber: PeerWithInfo  = generatePeerWithInfo(lowNumberStableHeader, MID_SCORE)
    val pwiMidScoreMidBlockNumber: PeerWithInfo  = generatePeerWithInfo(midNumberStableHeader, MID_SCORE)

    val peersMap = getPeersMap(pwiLowScoreHighBlockNumber, pwiMidScoreLowBlockNumber, pwiMidScoreMidBlockNumber)
    val resolver = NetworkStableHeaderResolver.lowDensity[IO](MIN_DENSITY_THRESHOLD)

    val test =
      SignallingRef[IO, Map[PeerId, PeerWithInfo]](peersMap).flatMap(peersWithInfo => resolver.resolve(peersWithInfo))

    val result = test.unsafeRunSync()
    result shouldBe StableHeaderFound(
      midNumberStableHeader,
      MID_SCORE,
      NonEmptyList.one(pwiMidScoreMidBlockNumber.id)
    )
  }

  it should "return an error when no peers are connected" in {
    checkNoPeerResult(NetworkStableHeaderResolver.lowDensity[IO](MIN_DENSITY_THRESHOLD))
  }

  it should "answer no stable for a peer with a score under minimum configured threshold" in {
    val score                      = MIN_DENSITY_THRESHOLD - EPSILON
    val stableHeader: ObftHeader   = BlockGenerators.obftBlockHeaderGen.sample.get
    val peerWithInfo: PeerWithInfo = generatePeerWithInfo(stableHeader, score)

    val peersMap = getPeersMap(peerWithInfo)
    val resolver = NetworkStableHeaderResolver.lowDensity[IO](MIN_DENSITY_THRESHOLD)

    val test =
      SignallingRef[IO, Map[PeerId, PeerWithInfo]](peersMap).flatMap(peersWithInfo => resolver.resolve(peersWithInfo))

    val result = test.unsafeRunSync()
    result shouldBe StableHeaderNotFound(MIN_DENSITY_THRESHOLD)
  }

  "RetryResolver" should "return immediately if the delegate succeeds" in {

    val header: ObftHeader = BlockGenerators.obftBlockHeaderGen.sample.get
    val pwi: PeerWithInfo  = generatePeerWithInfo(header, LOW_SCORE)
    val validAnswer        = StableHeaderFound(header, LOW_SCORE, NonEmptyList.one(pwi.id))

    val stepByStep = for {
      peersWithInfo <- SignallingRef[IO, Map[PeerId, PeerWithInfo]](Map.empty)
      answerSignal <-
        SignallingRef[IO, NetworkStableHeaderResult](validAnswer)
      resolver = NetworkStableHeaderResolver.withRetry(RETRY_COUNT, RETRY_DELAY, new SignalResolver(answerSignal))
      test     = resolver.resolve(peersWithInfo)
      control <- TestControl.execute(test)
      _       <- control.tick
      _ <- control.results.map(
             checkOutcomeFor(validAnswer)
           )
    } yield ()

    stepByStep.unsafeRunSync()
  }

  it should "fail after the expected retries" in {

    val stepByStep = for {
      peersWithInfo <- SignallingRef[IO, Map[PeerId, PeerWithInfo]](Map.empty)
      stableHeaderNotFoundSignal <-
        SignallingRef[IO, NetworkStableHeaderResult](StableHeaderNotFound(LOW_SCORE))
      resolver =
        NetworkStableHeaderResolver.withRetry(RETRY_COUNT, RETRY_DELAY, new SignalResolver(stableHeaderNotFoundSignal))
      test     = resolver.resolve(peersWithInfo)
      control <- TestControl.execute(test)
      _       <- control.tick
      _       <- control.results.map(result => result shouldBe None)
      _       <- control.advanceAndTick(RETRY_DELAY)
      _       <- control.results.map(result => result shouldBe None)
      _       <- control.advanceAndTick(RETRY_DELAY * (RETRY_COUNT - 1))
      _ <- control.results.map(
             checkOutcomeFor(StableHeaderNotFound(LOW_SCORE))
           )
    } yield ()

    stepByStep.unsafeRunSync()
  }

  it should "return as soon as the delegate resolver succeeds" in {

    val header: ObftHeader = BlockGenerators.obftBlockHeaderGen.sample.get
    val pwi: PeerWithInfo  = generatePeerWithInfo(header, LOW_SCORE)
    val validAnswer        = StableHeaderFound(header, LOW_SCORE, NonEmptyList.one(pwi.id))

    val stepByStep = for {
      peersWithInfo <- SignallingRef[IO, Map[PeerId, PeerWithInfo]](Map.empty)
      answerSignal <-
        SignallingRef[IO, NetworkStableHeaderResult](StableHeaderNotFound(LOW_SCORE))
      resolver = NetworkStableHeaderResolver.withRetry(RETRY_COUNT, RETRY_DELAY, new SignalResolver(answerSignal))
      test     = resolver.resolve(peersWithInfo)
      control <- TestControl.execute(test)
      _       <- control.tick
      _       <- control.results.map(result => result shouldBe None)
      _       <- answerSignal.set(validAnswer)
      _ <- control.results.map(result =>
             result shouldBe None
           ) // resolver should wait for the next tentative before returning result
      _ <- control.advanceAndTick(RETRY_DELAY)
      _ <- control.results.map(
             checkOutcomeFor(validAnswer)
           )
    } yield ()

    stepByStep.unsafeRunSync()
  }

  "CompositeResolver" should "return proper result" in {

    val header1: ObftHeader = BlockGenerators.obftBlockHeaderGen.sample.get
    val header2: ObftHeader = BlockGenerators.obftBlockHeaderGen.sample.get
    val peerId1: PeerId     = peerIdGen.sample.get
    val peerId2: PeerId     = peerIdGen.sample.get
    val validAnswer1        = StableHeaderFound(header1, HIGH_SCORE, NonEmptyList.one(peerId1))
    val invalidAnswer1      = NoAvailablePeer
    val validAnswer2        = StableHeaderFound(header2, HIGH_SCORE, NonEmptyList.one(peerId2))
    val invalidAnswer2      = StableHeaderNotFound(LOW_SCORE)

    checkComposite(validAnswer1, validAnswer2, validAnswer1)
    checkComposite(validAnswer1, invalidAnswer2, validAnswer1)
    checkComposite(invalidAnswer1, validAnswer2, validAnswer2)
    checkComposite(invalidAnswer1, invalidAnswer2, invalidAnswer2)
  }

  private def generatePeerWithInfo(stableHeader: ObftHeader, peerScore: Double): PeerWithInfo = {
    val peerWithInfo: PeerWithInfo = Generators.peerWithInfoGen.sample.get
    peerWithInfo.copy(peerInfo =
      peerWithInfo.peerInfo.copy(peerBranchScore = Some(StableHeaderScore(stableHeader, peerScore)))
    )
  }

  private def getPeersMap(pwis: PeerWithInfo*): Map[PeerId, PeerWithInfo] = pwis.map(pwi => pwi.id -> pwi).toMap

  private def checkOutcomeFor(expectedResult: NetworkStableHeaderResult)(
      outcome: Option[Outcome[cats.Id, Throwable, NetworkStableHeaderResult]]
  ): Assertion =
    outcome shouldBe Some(
      Outcome.Succeeded[cats.Id, Throwable, NetworkStableHeaderResult](
        expectedResult
      )
    )

  private def checkNoPeerResult(resolver: NetworkStableHeaderResolver[IO]): Unit = {
    val test =
      SignallingRef[IO, Map[PeerId, PeerWithInfo]](Map.empty).flatMap(peersWithInfo => resolver.resolve(peersWithInfo))
    val result = test.unsafeRunSync()
    result shouldBe NoAvailablePeer
  }

  private def checkComposite(
      value1: NetworkStableHeaderResult,
      value2: NetworkStableHeaderResult,
      expectedResult: NetworkStableHeaderResult
  ): Unit = {

    val test = for {
      peersWithInfo    <- SignallingRef[IO, Map[PeerId, PeerWithInfo]](Map.empty)
      answerSignal1    <- SignallingRef[IO, NetworkStableHeaderResult](value1)
      answerSignal2    <- SignallingRef[IO, NetworkStableHeaderResult](value2)
      resolver1         = new SignalResolver[IO](answerSignal1)
      resolver2         = new SignalResolver[IO](answerSignal2)
      compositeResolver = NetworkStableHeaderResolver.withFallback(resolver1, resolver2)
      result           <- compositeResolver.resolve(peersWithInfo)
    } yield result

    val result = test.unsafeRunSync()
    result shouldBe expectedResult
  }

  class SignalResolver[F[_]](value: Signal[F, NetworkStableHeaderResult]) extends NetworkStableHeaderResolver[F] {
    override def resolve(
        peersWithInfo: Signal[F, Map[PeerId, PeerWithInfo]]
    ): F[NetworkStableHeaderResult] = value.get
  }
}
