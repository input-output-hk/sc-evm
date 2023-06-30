package io.iohk.scevm.metrics.instruments

import cats.Applicative

/** A histogram that records the duration of time *between* two async events.
  * Time starts upon creation of the histogram and can be reported by calling `observe`.
  */
trait IntervalHistogram[F[_]] {
  def observe: F[Unit]
  def observe(exemplars: List[String]): F[Unit]
}

object IntervalHistogram {
  def noop[F[_]: Applicative]: IntervalHistogram[F] = new IntervalHistogram[F] {
    override def observe: F[Unit] = Applicative[F].unit

    override def observe(exemplars: List[String]): F[Unit] = Applicative[F].unit
  }
}
