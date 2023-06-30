package io.iohk.scevm.consensus.metrics

trait PhasesMetrics[F[_]] {

  /** Set the value to transition phase */
  def unsetPhase(): F[Unit]
  def setSynchronizationPhase(): F[Unit]
  def setConsensusPhase(): F[Unit]
}
