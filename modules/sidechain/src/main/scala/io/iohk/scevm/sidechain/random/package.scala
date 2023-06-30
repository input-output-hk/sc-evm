package io.iohk.scevm.sidechain

import cats.data.NonEmptyVector
import cats.effect.MonadCancelThrow
import cats.effect.std.Random
import cats.syntax.all._

package object random {
  implicit class RichRandomOps[F[_]: MonadCancelThrow](random: Random[F]) {
    def selectWeighted[T](candidates: NonEmptyVector[T])(density: T => Long): F[T] = {
      val cumulativeDensity = calculateCumulativeDensity(candidates, density)
      val densitySum        = cumulativeDensity.last._1
      random.nextLongBounded(densitySum).flatMap { randomNumber =>
        cumulativeDensity
          .collectFirst {
            case (densityUpperValue, item) if randomNumber < densityUpperValue => item.pure[F]
          }
          .getOrElse(MonadCancelThrow[F].raiseError(new IllegalStateException("shouldn't happen")))
      }
    }

    private def calculateCumulativeDensity[T](
        candidates: NonEmptyVector[T],
        density: T => Long
    ) =
      candidates
        .foldLeft(Vector.empty[(Long, T)]) { case (acc, item) =>
          val cumulativeDensitySoFar = acc.lastOption.map(_._1).getOrElse(0L)
          acc :+ ((cumulativeDensitySoFar + density(item)) -> item)
        }

  }
}
