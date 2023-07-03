package io.iohk.scevm.network

import akka.actor.ActorRef
import io.iohk.scevm.domain.NodeId

import java.net.InetSocketAddress
import scala.concurrent.duration.FiniteDuration

final case class ConnectedPeers(
    private val incomingPendingPeers: Map[PeerId, Peer],
    private val outgoingPendingPeers: Map[PeerId, Peer],
    private val handshakedPeers: Map[PeerId, Peer],
    private val pruningPeers: Map[PeerId, Peer],
    private val lastPruneTimestamp: Long
) {

  // FIXME: Kept only for compatibility purposes, should eventually be removed
  lazy val peers: Map[PeerId, Peer] = outgoingPendingPeers ++ handshakedPeers

  private lazy val allPeers: Map[PeerId, Peer] = outgoingPendingPeers ++ handshakedPeers ++ incomingPendingPeers

  private lazy val allPeersRemoteAddresses: Set[InetSocketAddress] = allPeers.values.map(_.remoteAddress).toSet
  def isConnectionHandled(remoteAddress: InetSocketAddress): Boolean =
    allPeersRemoteAddresses.contains(remoteAddress)

  /*
      We have the node id of our outgoing pending peers so we could use that in our checks, by rejecting a peer that
      handshaked to us with the same node id.
      However, with checking the node id of only handshaked peers we prioritize handshaked peers over pending ones,
      in the above mentioned case the repeated pending peer connection will eventually die out
   */
  private lazy val handshakedPeersNodeIds: Set[NodeId] = handshakedPeers.values.flatMap(_.nodeId).toSet
  def hasHandshakedWith(nodeId: NodeId): Boolean =
    handshakedPeersNodeIds.contains(nodeId)

  lazy val incomingPendingPeersCount: Int = incomingPendingPeers.size
  lazy val outgoingPendingPeersCount: Int = outgoingPendingPeers.size
  lazy val pendingPeersCount: Int         = incomingPendingPeersCount + outgoingPendingPeersCount

  lazy val incomingHandshakedPeersCount: Int = handshakedPeers.count { case (_, p) => p.incomingConnection }
  lazy val outgoingHandshakedPeersCount: Int = handshakedPeers.count { case (_, p) => !p.incomingConnection }
  lazy val handshakedPeersCount: Int         = handshakedPeers.size

  lazy val incomingPruningPeersCount: Int = pruningPeers.count { case (_, p) => p.incomingConnection }

  /** Sum of handshaked and pending peers. */
  lazy val outgoingPeersCount: Int = peers.count { case (_, p) => !p.incomingConnection }

  def getPeer(peerId: PeerId): Option[Peer] = peers.get(peerId)

  def addNewPendingPeer(pendingPeer: Peer): ConnectedPeers =
    if (pendingPeer.incomingConnection)
      copy(incomingPendingPeers = incomingPendingPeers + (pendingPeer.id -> pendingPeer))
    else
      copy(outgoingPendingPeers = outgoingPendingPeers + (pendingPeer.id -> pendingPeer))

  def promotePeerToHandshaked(peerAfterHandshake: Peer): ConnectedPeers =
    if (peerAfterHandshake.incomingConnection)
      copy(
        incomingPendingPeers = incomingPendingPeers - PeerId.fromRef(peerAfterHandshake.ref),
        handshakedPeers = handshakedPeers + (peerAfterHandshake.id -> peerAfterHandshake)
      )
    else
      copy(
        outgoingPendingPeers = outgoingPendingPeers - PeerId.fromRef(peerAfterHandshake.ref),
        handshakedPeers = handshakedPeers + (peerAfterHandshake.id -> peerAfterHandshake)
      )

  def removeTerminatedPeer(peerRef: ActorRef): (Iterable[PeerId], ConnectedPeers) = {
    val peersId = allPeers.collect { case (id, peer) if peer.ref == peerRef => id }

    (
      peersId,
      ConnectedPeers(
        incomingPendingPeers -- peersId,
        outgoingPendingPeers -- peersId,
        handshakedPeers -- peersId,
        pruningPeers -- peersId,
        lastPruneTimestamp = lastPruneTimestamp
      )
    )
  }

  def prunePeers(
      minAge: FiniteDuration,
      numPeers: Int,
      priority: PeerId => Double = _ => 0.0,
      incoming: Boolean = true,
      currentTimeMillis: Long = System.currentTimeMillis
  ): (Seq[Peer], ConnectedPeers) = {
    val ageThreshold = currentTimeMillis - minAge.toMillis
    if (lastPruneTimestamp > ageThreshold || numPeers == 0) {
      // Protect against hostile takeovers by limiting the frequency of pruning.
      (Seq.empty, this)
    } else {
      val candidates = handshakedPeers.values.filter(canPrune(incoming, ageThreshold)).toSeq

      val toPrune = candidates.sortBy(peer => priority(peer.id)).take(numPeers)

      val pruned = copy(
        pruningPeers = toPrune.foldLeft(pruningPeers) { case (acc, peer) =>
          acc + (peer.id -> peer)
        },
        lastPruneTimestamp = if (toPrune.nonEmpty) currentTimeMillis else lastPruneTimestamp
      )

      (toPrune, pruned)
    }
  }

  private def canPrune(incoming: Boolean, minCreateTimeMillis: Long)(peer: Peer): Boolean =
    peer.incomingConnection == incoming &&
      peer.createTimeMillis <= minCreateTimeMillis &&
      !pruningPeers.contains(peer.id)
}

object ConnectedPeers {
  def empty: ConnectedPeers = ConnectedPeers(Map.empty, Map.empty, Map.empty, Map.empty, 0L)
}
