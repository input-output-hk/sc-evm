package io.iohk.scevm.consensus.metrics

import cats.effect.IO
import cats.effect.testkit.TestControl
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.consensus.pos.PoSConfig._
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.SystemTime
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class TimeToFinalizationTrackerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  implicit private val systemTime: SystemTime[IO] = SystemTime.liveF[IO].ioValue

  private val metricsConfig = Metrics(15.seconds)

  "should return the correct value when evaluate is called on an already tracked BlockHash" in {
    val blockHash = BlockHash(Hex.decodeUnsafe("123"))

    val program = for {
      timeToFinalizationTracker <- TimeToFinalizationTrackerImpl[IO](metricsConfig)
      _                         <- timeToFinalizationTracker.track(blockHash)
      _                         <- IO.sleep(10.seconds)
      result                    <- timeToFinalizationTracker.evaluate(blockHash)
      _                         <- IO.pure(result shouldBe Some(10.seconds))
    } yield ()

    (for {

      control <- TestControl.execute(program)
      _       <- control.tickAll
      _       <- control.results.map(result => result.get.isSuccess shouldBe true)
    } yield succeed).ioValue
  }

  "should not update the tracked map after a second call to 'track' on an already tracked BlockHash" in {
    val blockHash = BlockHash(Hex.decodeUnsafe("123"))

    val program = for {
      timeToFinalizationTracker <- TimeToFinalizationTrackerImpl[IO](metricsConfig)
      _                         <- timeToFinalizationTracker.track(blockHash)
      _                         <- IO.sleep(5.seconds)
      _                         <- timeToFinalizationTracker.track(blockHash)
      _                         <- IO.sleep(5.seconds)
      result                    <- timeToFinalizationTracker.evaluate(blockHash)
      _                         <- IO.pure(result shouldBe Some(10.seconds))
    } yield ()

    (for {

      control <- TestControl.execute(program)
      _       <- control.tickAll
      _       <- control.results.map(result => result.get.isSuccess shouldBe true)
    } yield succeed).ioValue
  }

  "should work correctly when tracking multiple BlockHash" in {
    val blockHash1 = BlockHash(Hex.decodeUnsafe("123"))
    val blockHash2 = BlockHash(Hex.decodeUnsafe("456"))

    val program = for {
      timeToFinalizationTracker <- TimeToFinalizationTrackerImpl[IO](metricsConfig)
      _                         <- timeToFinalizationTracker.track(blockHash1)
      _                         <- IO.sleep(5.seconds)
      _                         <- timeToFinalizationTracker.track(blockHash2)
      _                         <- IO.sleep(5.seconds)
      result1                   <- timeToFinalizationTracker.evaluate(blockHash1)
      _                         <- IO.pure(result1 shouldBe Some(10.seconds))
      result2                   <- timeToFinalizationTracker.evaluate(blockHash2)
      _                         <- IO.pure(result2 shouldBe Some(5.seconds))
    } yield ()

    (for {

      control <- TestControl.execute(program)
      _       <- control.tickAll
      _       <- control.results.map(result => result.get.isSuccess shouldBe true)
    } yield succeed).ioValue
  }

  "should not call BlockMetrics when 'evaluate' is called on an untracked BlockHash" in {
    val blockHash = BlockHash(Hex.decodeUnsafe("123"))

    val program = for {
      timeToFinalizationTracker <- TimeToFinalizationTrackerImpl[IO](metricsConfig)
      result                    <- timeToFinalizationTracker.evaluate(blockHash)
      _                         <- IO.pure(result shouldBe None)
    } yield ()

    (for {

      control <- TestControl.execute(program)
      _       <- control.tickAll
      _       <- control.results.map(result => result.get.isSuccess shouldBe true)
    } yield succeed).ioValue
  }

  "should untracked too old BlockHash" in {
    val blockHash1 = BlockHash(Hex.decodeUnsafe("123"))
    val blockHash2 = BlockHash(Hex.decodeUnsafe("456"))

    val program = for {
      timeToFinalizationTracker <- TimeToFinalizationTrackerImpl[IO](metricsConfig)
      _                         <- timeToFinalizationTracker.track(blockHash1)
      _                         <- timeToFinalizationTracker.track(blockHash2)
      _                         <- IO.sleep(15.seconds)
      result1                   <- timeToFinalizationTracker.evaluate(blockHash1)
      _                         <- IO.pure(result1 shouldBe Some(15.seconds))
      result2                   <- timeToFinalizationTracker.evaluate(blockHash2)
      _                         <- IO.pure(result2 shouldBe None)
    } yield ()

    (for {

      control <- TestControl.execute(program)
      _       <- control.tickAll
      _       <- control.results.map(result => result.get.isSuccess shouldBe true)
    } yield succeed).ioValue
  }

  "should do nothing if metrics instrumentation is disabled" in {
    val blockHash = BlockHash(Hex.decodeUnsafe("123"))

    val program = for {
      timeToFinalizationTracker <- IO.pure(TimeToFinalizationTracker.noop[IO])
      _                         <- timeToFinalizationTracker.track(blockHash)
      _                         <- IO.sleep(10.seconds)
      result1                   <- timeToFinalizationTracker.evaluate(blockHash)
      _                         <- IO.pure(result1 shouldBe None)
    } yield ()

    (for {

      control <- TestControl.execute(program)
      _       <- control.tickAll
      _       <- control.results.map(result => result.get.isSuccess shouldBe true)
    } yield succeed).ioValue
  }

}
