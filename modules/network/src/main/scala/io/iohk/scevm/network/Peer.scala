package io.iohk.scevm.network

import akka.actor.ActorRef
import cats.{Eq, Show}
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.domain.NodeId

import java.math.BigInteger
import java.net.InetSocketAddress

final case class PeerId(value: String) {
  def kademliaDistance(otherPeerId: PeerId): BigInt = new BigInt(
    new BigInteger(ByteUtils.xor(value.getBytes, otherPeerId.value.getBytes))
  )
}

object PeerId {
  def fromRef(ref: ActorRef): PeerId = PeerId(ref.path.name)
  implicit val show: Show[PeerId]    = cats.derived.semiauto.show
  implicit val eqPeerId: Eq[PeerId]  = Eq.fromUniversalEquals
}

final case class Peer(
    id: PeerId,
    remoteAddress: InetSocketAddress,
    ref: ActorRef,
    incomingConnection: Boolean,
    nodeId: Option[NodeId] = None,
    createTimeMillis: Long = System.currentTimeMillis
) {
  def toShortString: String = s"Peer(id: $id, remoteAddress: $remoteAddress)"
}
object Peer {
  implicit val show: Show[Peer] = Show.show(_.toShortString)
}
