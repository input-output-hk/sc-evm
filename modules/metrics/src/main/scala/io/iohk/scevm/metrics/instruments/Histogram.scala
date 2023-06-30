package io.iohk.scevm.metrics.instruments

import cats.Applicative
import io.prometheus.client.{Histogram => ClientHistogram}

trait Histogram[F[_]] {
  def observe[A](fa: F[A]): F[A]
  def observe[A](fa: F[A], exemplars: Map[String, String]): F[A]
  def createTimer: F[HistogramTimer[F]]
}

object Histogram {
  private val namespace = "sc_evm"
  private val subsystem = "noop"

  val noopHistogram: ClientHistogram = {
    val builder = io.prometheus.client.Histogram
      .build()
      .name("no_op_time")
      .help("no_op_time")
      .namespace(namespace)
      .subsystem(subsystem)
    builder.withoutExemplars()
    builder.register()
  }

  def noop[F[_]: Applicative]: Histogram[F] = new Histogram[F] {
    override def observe[A](fa: F[A]): F[A]                                 = fa
    override def observe[A](fa: F[A], exemplars: Map[String, String]): F[A] = fa
    override def createTimer: F[HistogramTimer[F]]                          = Applicative[F].pure(HistogramTimer.noop)
  }

  def noOpClientHistogram(): ClientHistogram = noopHistogram
}
