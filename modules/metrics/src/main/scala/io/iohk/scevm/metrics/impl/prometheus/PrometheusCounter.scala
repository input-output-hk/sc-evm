package io.iohk.scevm.metrics.impl.prometheus

import cats.effect.kernel.Ref
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import io.iohk.scevm.metrics.instruments.Counter

final case class PrometheusCounter[F[_]: Sync] private (counter: Ref[F, io.prometheus.client.Counter])
    extends Counter[F] {
  override def inc(amount: Double): F[Unit] = counter.get.map(_.inc(amount))
  override def inc(amount: Double, exemplars: List[String]): F[Unit] =
    counter.get.map(_.incWithExemplar(amount, exemplars: _*))

  def unregister(): F[Unit] =
    counter.get.map(c => io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(c))
}

object PrometheusCounter {
  def apply[F[_]: Sync](
      namespace: String,
      subsystem: String,
      name: String,
      help: String,
      withExemplars: Boolean,
      labelNames: Option[List[String]]
  ): Resource[F, PrometheusCounter[F]] = {
    val createCounterF = for {
      counter <- Ref.ofEffect {
                   Sync[F].delay {
                     val builder = io.prometheus.client.Counter
                       .build()
                       .namespace(namespace)
                       .subsystem(subsystem)
                       .name(name)
                       .help(help)

                     if (withExemplars) builder.withExemplars() else builder.withoutExemplars()
                     labelNames.foreach(labels => builder.labelNames(labels: _*))
                     builder.register()
                   }
                 }
    } yield new PrometheusCounter[F](counter)

    Resource.make(createCounterF)(_.unregister())
  }
}
