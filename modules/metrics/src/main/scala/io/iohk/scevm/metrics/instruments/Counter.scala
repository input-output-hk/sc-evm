package io.iohk.scevm.metrics.instruments

import cats.Applicative

trait Counter[F[_]] {
  def inc(amount: Double): F[Unit]
  final def inc: F[Unit] = inc(1.0)

  def inc(amount: Double, exemplars: List[String]): F[Unit]
}
object Counter {
  def noop[F[_]: Applicative]: Counter[F] = new Counter[F] {
    override def inc(amount: Double): F[Unit]                          = Applicative[F].unit
    override def inc(amount: Double, exemplars: List[String]): F[Unit] = Applicative[F].unit
  }
}
