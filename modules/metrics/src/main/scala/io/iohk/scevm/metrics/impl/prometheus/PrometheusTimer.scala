package io.iohk.scevm.metrics.impl.prometheus

import cats.effect.{Ref, Sync}
import cats.syntax.all._
import io.iohk.scevm.metrics.instruments.HistogramTimer
import io.prometheus.client.Histogram.Timer

final case class PrometheusTimer[F[_]: Sync] private (timerRef: Ref[F, io.prometheus.client.Histogram.Timer])
    extends HistogramTimer[F] {

  override def observe(): F[Double] = timerRef.get.map(_.observeDuration())

  override def observe(exemplars: Map[String, String]): F[Double] =
    for {
      timer  <- timerRef.get
      labels  = exemplars.toList.flatMap { case (k, v) => List(k, v) }
      result <- Sync[F].delay(timer.observeDurationWithExemplar(labels: _*))
    } yield result
}

object PrometheusTimer {
  def apply[F[_]: Sync](prometheusHistogram: io.prometheus.client.Histogram): F[HistogramTimer[F]] = for {
    timer    <- Sync[F].delay(prometheusHistogram.startTimer())
    timerRef <- Ref.of[F, Timer](timer)
  } yield new PrometheusTimer(timerRef)
}
