package io.iohk.scevm.metrics.instruments

import cats.Applicative

trait HistogramTimer[F[_]] {
  def observe(): F[Double]
  def observe(exemplars: Map[String, String]): F[Double]
}

object HistogramTimer {
  def noop[F[_]: Applicative]: HistogramTimer[F] = new HistogramTimer[F] {
    override def observe(): F[Double]                               = Applicative[F].pure(0.0)
    override def observe(exemplars: Map[String, String]): F[Double] = Applicative[F].pure(0.0)
  }
}
