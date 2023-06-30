package io.iohk.scevm

import cats.effect.IO
import fs2.{Pure, Stream}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.StreamUtils.RichFs2Stream
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StreamUtilsTest
    extends AnyWordSpec
    with IOSupport
    with ScalaFutures
    with Matchers
    with ScalaCheckPropertyChecks
    with NormalPatience {

  "evalScan1" in {
    forAll { s: Stream[Pure, Int] =>
      val f: (Int, Int) => IO[Int] = (a: Int, b: Int) => IO.pure(a + b)
      val g: (Int, Int) => Int     = (a: Int, b: Int) => a + b
      val expected = s.toList
        .scanLeft(Option.empty[Int]) {
          case (Some(prev), cur) =>
            Some(g(prev, cur))
          case (None, cur) => Some(cur)
        }
        .collect { case Some(v) => v }
      s.covary[IO].evalScan1[IO, Int](f).assertEmits(expected).ioValue
    }
  }

  "evalFilterWithPrevious" in {
    forAll { s: Stream[Pure, Int] =>
      val f: (Int, Int) => IO[Boolean] = (a: Int, b: Int) => IO.pure(a > b)
      val g: (Int, Int) => Boolean     = (a: Int, b: Int) => a > b

      val expected = s.filterWithPrevious(g).compile.toList
      s.covary[IO]
        .evalFilterWithPrevious(f)
        .assertEmits(expected)
        .ioValue
    }
  }

  implicit class StreamAssertionsOf[A](val str: Stream[IO, A]) {

    def assertEmits(expectedOutputs: List[A]): IO[Unit] =
      str.compile.toList.map(r => r should be(expectedOutputs))
  }

  implicit def pureStreamGenerator[A: Arbitrary]: Arbitrary[Stream[Pure, A]] =
    Arbitrary {
      val genA = arbitrary[A]
      Gen.frequency(
        1 -> Gen.const(Stream.empty),
        5 -> smallLists(genA).map(as => Stream.emits(as)),
        5 -> smallLists(genA).map(as => Stream.emits(as).chunkLimit(1).unchunks),
        5 -> smallLists(smallLists(genA))
          .map(_.foldLeft(Stream.empty.covaryOutput[A])((acc, as) => acc ++ Stream.emits(as))),
        5 -> smallLists(smallLists(genA))
          .map(_.foldRight(Stream.empty.covaryOutput[A])((as, acc) => Stream.emits(as) ++ acc))
      )
    }

  def smallLists[A](genA: Gen[A]): Gen[List[A]] =
    Gen.posNum[Int].flatMap(n0 => Gen.listOfN(n0 % 20, genA))
}
