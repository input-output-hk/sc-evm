package io.iohk.scevm.metrics.impl.prometheus

import cats.effect.{Ref, Resource, Sync}
import cats.syntax.all._
import io.iohk.scevm.metrics.instruments.IntervalHistogram

final case class PrometheusIntervalHistogram[F[_]: Sync] private (
    histogramTimerPair: Ref[F, (io.prometheus.client.Histogram, io.prometheus.client.Histogram.Timer)]
) extends IntervalHistogram[F] {

  override def observe: F[Unit] = histogramTimerPair.update { case (histogram, timer) =>
    timer.observeDuration()
    (histogram, histogram.startTimer())
  }

  override def observe(exemplars: List[String]): F[Unit] = histogramTimerPair.update { case (histogram, timer) =>
    timer.observeDurationWithExemplar(exemplars: _*)
    (histogram, histogram.startTimer())
  }

  def unregister(): F[Unit] =
    histogramTimerPair.get.map { case (histo, _) =>
      io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(histo)
    }

}

object PrometheusIntervalHistogram {
  def apply[F[_]: Sync](
      namespace: String,
      subsystem: String,
      name: String,
      help: String,
      withExemplars: Boolean,
      labelNames: Option[List[String]],
      buckets: Option[List[Double]]
  ): Resource[F, PrometheusIntervalHistogram[F]] = {
    val createHistogramF = for {
      histogram <- Ref.ofEffect {
                     Sync[F].delay {
                       val builder = io.prometheus.client.Histogram
                         .build()
                         .name(name)
                         .help(help)
                         .namespace(namespace)
                         .subsystem(subsystem)
                       if (withExemplars) builder.withExemplars() else builder.withoutExemplars()
                       labelNames.foreach(labels => builder.labelNames(labels: _*))
                       buckets.foreach(bs => builder.buckets(bs: _*))
                       val histo = builder.register()
                       val timer = histo.startTimer()
                       (histo, timer)
                     }
                   }
    } yield new PrometheusIntervalHistogram(histogram)

    Resource.make(
      createHistogramF
    )(_.unregister())
  }
}
