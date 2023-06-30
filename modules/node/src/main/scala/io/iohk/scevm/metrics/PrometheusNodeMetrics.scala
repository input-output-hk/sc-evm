package io.iohk.scevm.metrics

import cats.effect.{Resource, Sync}
import io.iohk.scevm.metrics.impl.prometheus.PrometheusGauge

import java.time.format.DateTimeFormatter
import java.time.{OffsetDateTime, ZoneOffset}

final case class PrometheusNodeMetrics[F[_]: Sync] private (private val gitRevisionGauge: PrometheusGauge[F])
    extends NodeMetrics[F] {

  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

  override def recordGitRevision(gitRevision: String): F[Unit] = {
    val currentTime = OffsetDateTime.now()
    gitRevisionGauge.set(1, List(gitRevision, currentTime.withOffsetSameInstant(ZoneOffset.UTC).format(formatter)))
  }
}

object PrometheusNodeMetrics {
  private val namespace = "sc_evm"
  private val subsystem = "node"

  def apply[F[_]: Sync]: Resource[F, PrometheusNodeMetrics[F]] =
    for {
      gitRevisionGauge <- PrometheusGauge(
                            namespace,
                            subsystem,
                            "git_revision",
                            "Git hash corresponding to the deployed version of EVM sidechain",
                            Some(List("git_revision", "node_start_time"))
                          )
    } yield new PrometheusNodeMetrics(gitRevisionGauge)
}
