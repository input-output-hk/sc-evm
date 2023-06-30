package io.iohk.scevm.network

import cats.effect.{IO, Resource}
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.DurationInt

class FilteredTopicSpec extends AnyWordSpec with Matchers with ScalaFutures with NormalPatience with IOSupport {
  "publish1 method should only publish valid data according to a given filter" in {
    val filter = (element: String) =>
      element match {
        case "A" | "C" => IO.pure(false)
        case _         => IO.pure(true)
      }

    implicit val signal: SignallingRef[IO, Boolean] = SignallingRef.of[IO, Boolean](false).unsafeRunSync()

    (for {
      controlledTopic <- FilteredTopic(filter)
      publisher = Stream
                    .emits(Seq("A", "B", "C", "D"))
                    .covary[IO]
                    .delayBy(100.millis)
                    .evalTap(v => controlledTopic.publish1(v))
                    .drain

      quitStream = Stream.eval(signal.set(true)).delayBy(100.millis)

      subscriber = controlledTopic
                     .subscribe(1)
                     .interruptWhen(signal)

      result <- Resource.eval(subscriber.concurrently(publisher ++ quitStream).compile.toList)
    } yield result).use(result => IO.pure(result shouldBe List("B", "D"))).ioValue
  }

  "publish method should only publish valid data according to a given filter" in {
    val filter = (element: String) =>
      element match {
        case "A" | "C" => IO.pure(false)
        case _         => IO.pure(true)
      }

    implicit val signal: SignallingRef[IO, Boolean] = SignallingRef.of[IO, Boolean](false).unsafeRunSync()

    (for {
      controlledTopic <- FilteredTopic(filter)
      publisher = Stream
                    .emits(Seq("A", "B", "C", "D"))
                    .covary[IO]
                    .delayBy(100.millis)
                    .through(controlledTopic.publish)
                    .drain

      quitStream = Stream.eval(signal.set(true)).delayBy(100.millis)

      subscriber = controlledTopic
                     .subscribe(1)
                     .interruptWhen(signal)

      result <- Resource.eval(subscriber.concurrently(publisher ++ quitStream).compile.toList)
    } yield result).use(result => IO.pure(result shouldBe List("B", "D"))).ioValue
  }
}
