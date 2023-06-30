package io.iohk.scevm.metrics.instruments

import cats.MonadThrow
import cats.syntax.all._

class SFHistogram[F[_]: MonadThrow](
    callHistogram: Histogram[F],
    successCounter: Counter[F],
    failureCounter: Counter[F]
) {

  def observe[A](fa: F[A], exemplars: Map[String, String]): F[A] = MonadThrow[F].onError(
    callHistogram.observe(fa, exemplars) <* successCounter.inc(1)
  ) { case _ => failureCounter.inc(1) }

  def observe[T](ft: F[T]): F[T] =
    MonadThrow[F].onError(
      callHistogram.observe(ft) <* successCounter.inc(1)
    ) { case _ => failureCounter.inc(1) }
}
