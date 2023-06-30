package io.iohk.scevm.metrics.impl.prometheus

import cats.effect.Sync
import cats.effect.kernel.{Ref, Resource}
import cats.syntax.all._
import io.iohk.scevm.metrics.instruments.Gauge

final case class PrometheusGauge[F[_]: Sync] private (gauge: Ref[F, io.prometheus.client.Gauge]) extends Gauge[F] {
  override def inc(amount: Double): F[Unit]                       = gauge.get.map(_.inc(amount))
  override def dec(amount: Double): F[Unit]                       = gauge.get.map(_.dec(amount))
  override def set(amount: Double): F[Unit]                       = gauge.get.map(_.set(amount))
  override def set(amount: Double, labels: List[String]): F[Unit] = gauge.get.map(_.labels(labels: _*).set(amount))

  def unregister(): F[Unit] =
    gauge.get.map(g => io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(g))
}

object PrometheusGauge {
  def apply[F[_]: Sync](
      namespace: String,
      subsystem: String,
      name: String,
      help: String,
      labelNames: Option[List[String]] = None
  ): Resource[F, PrometheusGauge[F]] = {
    val createGaugeF = for {
      gauge <- Ref.ofEffect {
                 Sync[F].delay {
                   val builder = io.prometheus.client.Gauge
                     .build()
                     .namespace(namespace)
                     .subsystem(subsystem)
                     .name(name)
                     .help(help)

                   labelNames.foreach(labels => builder.labelNames(labels: _*))
                   builder.register()
                 }
               }
    } yield new PrometheusGauge[F](gauge)

    Resource.make(createGaugeF)(_.unregister())
  }
}
