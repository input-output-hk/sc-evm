package io.iohk.scevm.consensus.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusGauge
import io.iohk.scevm.metrics.instruments.Gauge

final class PrometheusPhasesMetrics[F[_]] private (phaseGauge: Gauge[F]) extends PhasesMetrics[F] {
  override def unsetPhase(): F[Unit]              = phaseGauge.set(0)
  override def setSynchronizationPhase(): F[Unit] = phaseGauge.set(1)
  override def setConsensusPhase(): F[Unit]       = phaseGauge.set(2)
}

object PrometheusPhasesMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "phase"

  def apply[F[_]: Sync]: Resource[F, PrometheusPhasesMetrics[F]] =
    for {
      phaseGauge <- PrometheusGauge(
                      namespace,
                      subsystem,
                      "duration",
                      "Duration of each phase (initialization, synchronization, consensus, ...)"
                    )
    } yield new PrometheusPhasesMetrics(phaseGauge)

}
