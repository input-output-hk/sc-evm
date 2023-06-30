package io.iohk.scevm.metrics.impl.prometheus

import cats.effect.kernel.{Resource, Sync}
import io.iohk.scevm.metrics.instruments.SFHistogram

object PrometheusSFHistogram {
  def apply[F[_]: Sync](namespace: String, subsystem: String, name: String): Resource[F, SFHistogram[F]] =
    for {
      callHistogram <-
        PrometheusHistogram(
          namespace,
          subsystem,
          s"${name}_executionTime",
          s"Execution time of $name",
          false,
          None,
          None
        )
      successCounter <-
        PrometheusCounter(namespace, subsystem, s"${name}_success", s"Success count of $name", false, None)
      failureCounter <-
        PrometheusCounter(namespace, subsystem, s"${name}_failure", s"Failure count of $name", false, None)
    } yield new SFHistogram(callHistogram, successCounter, failureCounter)
}
