package io.iohk.scevm.utils

import cats.effect.{Ref, Sync}
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.NodeId
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters

import java.net.InetSocketAddress

sealed trait ServerStatus
object ServerStatus {
  case object NotListening                               extends ServerStatus
  final case class Listening(address: InetSocketAddress) extends ServerStatus
}

sealed trait NodeState
object NodeState {
  final case object Starting                 extends NodeState
  final case object Syncing                  extends NodeState
  final case object Running                  extends NodeState
  final case object TransitioningFromSyncing extends NodeState
}

final case class NodeStatus private (key: AsymmetricCipherKeyPair, nodeState: NodeState, serverStatus: ServerStatus) {
  import moveme._
  val nodeId: NodeId = NodeId(ByteString(key.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId))
}

object NodeStatus {
  def apply(key: AsymmetricCipherKeyPair): NodeStatus =
    new NodeStatus(key, NodeState.Starting, ServerStatus.NotListening)
}

trait NodeStateProvider[F[_]] {
  def getNodeState: F[NodeState]
}

trait NodeStateUpdater[F[_]] {

  /** Set the new state of the node. It should be a no-op if the new state is "Starting".
    * @param nodeState the new state
    * @return
    */
  def setNodeState(nodeState: NodeState): F[Unit]
}

trait ServerStatusProvider[F[_]] {
  def setServerStatus(serverStatus: ServerStatus): F[Unit]
}

trait NodeStatusProvider[F[_]] extends NodeStateProvider[F] with NodeStateUpdater[F] with ServerStatusProvider[F] {
  def getNodeStatus: F[NodeStatus]
}

object NodeStatusProvider {

  def apply[F[_]: Sync](key: AsymmetricCipherKeyPair): F[NodeStatusProvider[F]] =
    for {
      ref <- Ref.of[F, NodeStatus](NodeStatus(key))
    } yield new NodeStatusProvider[F] {
      override def getNodeStatus: F[NodeStatus] = ref.get
      override def getNodeState: F[NodeState]   = ref.get.map(_.nodeState)
      override def setNodeState(nodeState: NodeState): F[Unit] = nodeState match {
        case NodeState.Starting => Sync[F].unit
        case state              => ref.update(_.copy(nodeState = state))
      }
      override def setServerStatus(serverStatus: ServerStatus): F[Unit] =
        ref.update(_.copy(serverStatus = serverStatus))
    }
}

// TODO copied here to avoid dependency on network module
// scalastyle:off object.name
object moveme {
  implicit class ECPublicKeyParametersNodeId(val pubKey: ECPublicKeyParameters) extends AnyVal {
    def toNodeId: Array[Byte] =
      pubKey
        .asInstanceOf[ECPublicKeyParameters]
        .getQ
        .getEncoded(false)
        .drop(1) // drop type info
  }
}
