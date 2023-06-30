package io.iohk.scevm.network

import cats.effect.Sync
import cats.syntax.all._
import fs2.concurrent.Signal
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.network.p2p.Message
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlock
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed trait PeerEvent

object PeerEvent {
  final case class MessageFromPeer(peerId: PeerId, message: Message)              extends PeerEvent
  final case class PeerDisconnected(peerId: PeerId)                               extends PeerEvent
  final case class PeerHandshakeSuccessful(peer: Peer, handshakeResult: PeerInfo) extends PeerEvent

  def filter[F[_]: Sync](peerEvent: PeerEvent)(implicit currentBranchSignal: Signal[F, CurrentBranch]): F[Boolean] = {
    val logger: Logger[F] = Slf4jLogger.getLogger[F]

    currentBranchSignal.get.flatMap { currentBranch =>
      peerEvent match {
        case PeerEvent.MessageFromPeer(_, message: NewBlock) if message.block.number <= currentBranch.stable.number =>
          logger.trace(
            s"NewBlock message rejected because it's related to the stable part of the blockchain (stable number = ${currentBranch.stable.number}): $peerEvent"
          ) >> Sync[F].pure(false)
        case _ => Sync[F].pure(true)
      }
    }
  }
}
