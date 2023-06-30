package io.iohk.scevm.metrics.impl.prometheus

import cats.effect.kernel.Ref
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import io.iohk.scevm.metrics.instruments.{Histogram, HistogramTimer}
import io.prometheus.client.{Histogram => ClientHistogram}

final case class PrometheusHistogram[F[_]: Sync] private (histogramRef: Ref[F, io.prometheus.client.Histogram])
    extends Histogram[F] {
  override def observe[A](fa: F[A]): F[A] =
    for {
      histogram <- histogramRef.get
      timer     <- Sync[F].delay(histogram.startTimer())
      result    <- fa
      _         <- Sync[F].delay(timer.observeDuration())
    } yield result

  override def observe[A](fa: F[A], exemplars: Map[String, String]): F[A] =
    for {
      histogram <- histogramRef.get
      labels     = exemplars.toList.flatMap { case (k, v) => List(k, v) }
      timer     <- Sync[F].delay(histogram.startTimer())
      result    <- fa
      _         <- Sync[F].delay(timer.observeDurationWithExemplar(labels: _*))
    } yield result

  def unregister(): F[Unit] =
    histogramRef.get.map(h => io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(h))

  override def createTimer: F[HistogramTimer[F]] = histogramRef.get.flatMap(hst => PrometheusTimer[F](hst))
}

object PrometheusHistogram {
  def apply[F[_]: Sync](
      namespace: String,
      subsystem: String,
      name: String,
      help: String,
      withExemplars: Boolean,
      labelNames: Option[List[String]],
      buckets: Option[List[Double]]
  ): Resource[F, PrometheusHistogram[F]] = {
    val createHistogramF: F[PrometheusHistogram[F]] = for {
      histogram <- Ref.ofEffect {
                     Sync[F].delay(
                       createUnwrappedHistogram(namespace, subsystem, name, help, withExemplars, labelNames, buckets)
                     )
                   }
    } yield new PrometheusHistogram(histogram)

    Resource.make(createHistogramF)(_.unregister())
  }

  def createUnwrappedHistogram(
      namespace: String,
      subsystem: String,
      name: String,
      help: String,
      withExemplars: Boolean,
      labelNames: Option[List[String]],
      buckets: Option[List[Double]]
  ): ClientHistogram = {
    val builder = io.prometheus.client.Histogram
      .build()
      .name(name)
      .help(help)
      .namespace(namespace)
      .subsystem(subsystem)
    if (withExemplars) builder.withExemplars() else builder.withoutExemplars()
    labelNames.foreach(labels => builder.labelNames(labels: _*))
    buckets.foreach(bs => builder.buckets(bs: _*))
    builder.register()
  }
}
