package io.iohk.scevm.metrics.instruments

import cats.Applicative

trait Gauge[F[_]] {
  def inc(amount: Double): F[Unit]
  final def inc: F[Unit] = inc(1.0)

  def dec(amount: Double): F[Unit]
  final def dec: F[Unit] = dec(1.0)

  def set(amount: Double): F[Unit]
  def set(amount: Double, labels: List[String]): F[Unit]
}

object Gauge {
  def noop[F[_]: Applicative]: Gauge[F] = new Gauge[F] {
    override def inc(amount: Double): F[Unit] = Applicative[F].unit

    override def dec(amount: Double): F[Unit] = Applicative[F].unit

    override def set(amount: Double): F[Unit] = Applicative[F].unit

    override def set(amount: Double, labels: List[String]): F[Unit] = Applicative[F].unit
  }
}
