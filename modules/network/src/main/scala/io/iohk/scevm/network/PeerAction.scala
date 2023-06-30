package io.iohk.scevm.network

import akka.actor.ActorRef
import cats.data.NonEmptyList
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import fs2.concurrent.{Signal, Topic}
import fs2.{Pipe, Stream}
import io.iohk.scevm.network.p2p.Message
import io.iohk.scevm.network.p2p.messages.OBFT1.RequestMessage
import org.typelevel.log4cats.LoggerFactory

sealed trait PeerAction {
  def message: Message
}

object PeerAction {
  final case class MessageToPeer(peerId: PeerId, message: Message)                        extends PeerAction
  final case class MessageToAll(message: RequestMessage)                                  extends PeerAction
  final case class MessageToPeers(peerIds: NonEmptyList[PeerId], message: RequestMessage) extends PeerAction

  def unapply(peerAction: PeerAction): Some[Message] =
    Some(peerAction.message)

  def createTopic[F[_]: Concurrent]: F[Topic[F, PeerAction]] = Topic[F, PeerAction]

  /** Converts and filters messages so that they always specify at least one connected peer.
    *
    * @param peers signal as produced by PeerWithInfo.aggregateState
    * @return FS2 pipe that narrows PeerAction to MessageToPeer
    */
  def messagesToConnectedPeers[F[_]: LoggerFactory](
      peers: Signal[F, Map[PeerId, PeerWithInfo]]
  ): Pipe[F, PeerAction, MessageToPeer] = {
    implicit val logger = LoggerFactory[F].getLogger

    _.zip(peers.continuous)
      .flatMap {
        case (mtp: MessageToPeer, _) => Stream(mtp)

        case (MessageToPeers(peerIds, message), peers) if peers.nonEmpty =>
          val preferredPeers = peers.filter { case (peerId, _) => peerIds.contains_(peerId) }
          Stream.emits(preferredPeers.keys.map(MessageToPeer(_, message)).toSeq)

        case (MessageToAll(message), peers) if peers.nonEmpty =>
          Stream.emits(peers.keys.map(MessageToPeer(_, message)).toSeq)

        case (peerAction, _) =>
          Stream.exec(logger.warn(s"No viable peer to send message; outgoing message discarded: $peerAction"))
      }
  }

  def sendMessages[F[_]: Sync: LoggerFactory](
      peerManagerActor: ActorRef,
      peers: Signal[F, Map[PeerId, PeerWithInfo]]
  ): Pipe[F, PeerAction, Unit] =
    _.through(messagesToConnectedPeers(peers)).evalTap { case MessageToPeer(peerId, message) =>
      Sync[F].delay(peerManagerActor ! PeerManagerActor.SendMessage(message, peerId))
    }.drain

}
