package io.iohk.scevm.sidechain

import cats.data.NonEmptyVector
import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all._
import io.iohk.scevm.sidechain.random.RichRandomOps
import io.iohk.scevm.testing.IOSupport
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class WeightedRandomSpec extends AnyWordSpec with Matchers with ScalaFutures with IOSupport with IntegrationPatience {

  "should have distribution according to the weights" in {
    val testValues = NonEmptyVector.of(10, 50, 30, 0, 20)
    val rounds     = 10_000
    val rawResults = (0 to rounds).toList.traverse { i =>
      for {
        random        <- Random.scalaUtilRandomSeedInt[IO](i)
        currentResult <- random.selectWeighted(testValues)(_.toLong)
      } yield currentResult
    }.ioValue
    val result = rawResults.groupBy(identity).view.mapValues(_.size)
    result.foreach { case (value, occurrences) =>
      val sumOfTestValues = testValues.toVector.sum
      // this is a weak constraint it doesn't have to hold for every test
      // hence the hardcoded seed values
      equalWithTolerance(value, occurrences, rounds * value / sumOfTestValues)
    }
  }

  private def equalWithTolerance(testValue: Int, occurrences: Int, expectedOccurrences: Int) = {
    val tolerance =
      if (testValue == 0) {
        0.0
      } else {
        1.0 / testValue
      }
    assert(occurrences <= (1 + tolerance) * expectedOccurrences && occurrences >= (1 - tolerance) * expectedOccurrences)
  }
}
