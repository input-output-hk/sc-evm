package io.iohk.scevm.network

import cats.Show
import cats.effect.Sync
import fs2.Pipe
import io.iohk.scevm.network.domain.PeerBranchScore
import io.iohk.scevm.network.metrics.NetworkMetrics
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.StableHeaders
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class PeerWithInfo(peer: Peer, peerInfo: PeerInfo) {
  def id: PeerId = peer.id

  def updatePeerBranchScore(peerBranchScore: PeerBranchScore): PeerWithInfo =
    if (peerInfo.peerBranchScore.exists(_.stable.number >= peerBranchScore.stable.number))
      this
    else
      copy(peerInfo = peerInfo.withPeerBranchScore(peerBranchScore))
}

object PeerWithInfo {

  import cats.syntax.all._

  implicit val show: Show[PeerWithInfo] = cats.derived.semiauto.show

  // scalastyle:off method.length
  def aggregateState[F[_]: Sync](
      networkMetrics: NetworkMetrics[F]
  ): Pipe[F, PeerEvent, Map[PeerId, PeerWithInfo]] =
    stream => {
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
      stream
        .collect {
          case event @ PeerEvent.MessageFromPeer(_, message) if message.isInstanceOf[OBFT1.NewBlock]      => event
          case event @ PeerEvent.MessageFromPeer(_, message) if message.isInstanceOf[OBFT1.StableHeaders] => event
          case event: PeerEvent.PeerDisconnected                                                          => event
          case event: PeerEvent.PeerHandshakeSuccessful                                                   => event
        }
        .evalScan(Map[PeerId, PeerWithInfo]()) {
          case (peersWithInfo, PeerEvent.MessageFromPeer(peerId, OBFT1.NewBlock(block))) =>
            logger
              .debug(show"Received NewBlock $block from peer $peerId")
              .as(peersWithInfo)

          case (peersWithInfo, PeerEvent.MessageFromPeer(peerId, StableHeaders(_, stableHeader, ancestors))) =>
            for {
              _              <- logger.debug(show"Stable Header for peer $peerId: $stableHeader")
              peerBranchScore = PeerBranchScore.from(stableHeader, ancestors.headOption)
            } yield peersWithInfo.updatedWith(peerId)(peerWithInfoOpt =>
              peerWithInfoOpt.map(_.updatePeerBranchScore(peerBranchScore))
            )

          case (peersWithInfo, PeerEvent.PeerDisconnected(peerId)) =>
            peersWithInfo.get(peerId) match {
              case Some(PeerWithInfo(peer, _)) =>
                for {
                  _ <- {
                    if (peer.incomingConnection)
                      networkMetrics.handshakedIncomingPeersGauge.dec
                    else
                      networkMetrics.handshakedOutgoingPeersGauge.dec
                  }
                  _ <- logger.info(s"Peer ${peer.toShortString} disconnected")
                } yield peersWithInfo - peerId
              case None =>
                (if (peerId.value.contains(':')) { // Before handshaking, the PeerId has the following format <ip>:<port>
                   logger.debug(s"Peer $peerId has stopped before being promoted to a HandshakedPeer")
                 } else {
                   logger.error(
                     s"Peer $peerId has not been removed from peers list after its disconnection because it is an unknown peer." +
                       s"This might be caused by a bug. \n Current known peers: $peersWithInfo"
                   )
                 }) >> Sync[F].pure(peersWithInfo)
            }

          case (peersWithInfo, PeerEvent.PeerHandshakeSuccessful(peer, peerInfo)) =>
            for {
              _ <- logger.info(s"Peer ${peer.toShortString} connected")
              _ <- {
                if (peer.incomingConnection)
                  networkMetrics.handshakedIncomingPeersGauge.inc
                else
                  networkMetrics.handshakedOutgoingPeersGauge.inc
              }
            } yield peersWithInfo + (peer.id -> PeerWithInfo(peer, peerInfo))

          case (peersWithInfo, _) => Sync[F].pure(peersWithInfo)
        }
    }

  def logPeersDetails[F[_]: Sync](peers: Map[PeerId, PeerWithInfo]): F[Unit] = {
    val logger               = Slf4jLogger.getLogger[F]
    val (incoming, outgoing) = peers.partition(_._2.peer.incomingConnection)
    for {
      _ <-
        logger.debug(
          s"Connected to ${peers.keys.size} peers: ${incoming.keys.size} incoming peers and ${outgoing.keys.size} outgoing peers."
        )
      _ <- printStatement(incoming.values.toList).traverse(logger.debug(_))
      _ <- printStatement(outgoing.values.toList).traverse(logger.debug(_))
    } yield ()
  }

  // scalastyle:off line.size.limit
  private def printStatement(peersInfo: List[PeerWithInfo]): List[String] = peersInfo.map { peerWithInfo =>
    show"Peer info: Id ${peerWithInfo.peer.id}, Incoming: ${peerWithInfo.peer.incomingConnection}, Remote address: ${peerWithInfo.peer.remoteAddress.toString}, Create time: ${peerWithInfo.peer.createTimeMillis} millis, ${peerWithInfo.peerInfo})"
  }

}
