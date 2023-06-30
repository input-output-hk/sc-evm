package io.iohk.scevm.consensus.metrics

import cats.effect.Sync

final case class NoOpPhasesMetrics[F[_]: Sync]() extends PhasesMetrics[F] {
  override def unsetPhase(): F[Unit]              = Sync[F].unit
  override def setSynchronizationPhase(): F[Unit] = Sync[F].unit
  override def setConsensusPhase(): F[Unit]       = Sync[F].unit
}
