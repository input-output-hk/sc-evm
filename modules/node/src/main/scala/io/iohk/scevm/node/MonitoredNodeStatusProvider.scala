package io.iohk.scevm.node

import cats.Monad
import cats.syntax.all._
import io.iohk.scevm.consensus.metrics.PhasesMetrics
import io.iohk.scevm.utils.{NodeState, NodeStatus, NodeStatusProvider, ServerStatus}

class MonitoredNodeStatusProvider[F[_]: Monad](monitored: NodeStatusProvider[F], phasesMetrics: PhasesMetrics[F])
    extends NodeStatusProvider[F] {
  override def getNodeStatus: F[NodeStatus]                         = monitored.getNodeStatus
  override def setServerStatus(serverStatus: ServerStatus): F[Unit] = monitored.setServerStatus(serverStatus)
  override def getNodeState: F[NodeState]                           = monitored.getNodeState
  override def setNodeState(nodeState: NodeState): F[Unit] =
    monitored.setNodeState(nodeState) >> (nodeState match {
      case NodeState.Starting                 => Monad[F].unit
      case NodeState.Syncing                  => phasesMetrics.setSynchronizationPhase()
      case NodeState.Running                  => phasesMetrics.setConsensusPhase()
      case NodeState.TransitioningFromSyncing => phasesMetrics.unsetPhase()
    })
}
