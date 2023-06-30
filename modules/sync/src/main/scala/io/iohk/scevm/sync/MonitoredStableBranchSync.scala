package io.iohk.scevm.sync

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.sync.MonitoredStableBranchSync.NodeStateUpdater
import io.janstenpickle.trace4cats.inject.Trace

class MonitoredStableBranchSync[F[_]: Sync: Trace](
    monitored: StableBranchSync[F],
    nodeStatusUpdater: NodeStateUpdater[F]
) extends StableBranchSync[F] {

  override def catchup(): F[StableBranchSync.StableBranchSyncResult] = {
    val program = for {
      _      <- nodeStatusUpdater.setSynchronizationPhase()
      result <- monitored.catchup()
      _      <- nodeStatusUpdater.unsetPhase()
    } yield result

    Trace[F].span("StableBranchSync-catchup")(program)
  }

}
object MonitoredStableBranchSync {
  trait NodeStateUpdater[F[_]] {
    def setSynchronizationPhase(): F[Unit]
    def unsetPhase(): F[Unit]
  }
}
