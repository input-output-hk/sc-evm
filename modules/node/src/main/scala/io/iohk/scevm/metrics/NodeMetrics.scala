package io.iohk.scevm.metrics

trait NodeMetrics[F[_]] {
  def recordGitRevision(gitRevision: String): F[Unit]
}
