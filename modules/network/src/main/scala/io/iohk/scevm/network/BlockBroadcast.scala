package io.iohk.scevm.network

import cats.effect.Sync
import cats.implicits.showInterpolator
import fs2.{Pipe, Stream}
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.network.p2p.messages.OBFT1._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object BlockBroadcast {
  def toPeerActions[F[_]: Sync]: Pipe[F, (ObftBlock, Set[Peer]), PeerAction] = { stream =>
    val logger = Slf4jLogger.getLogger[F]

    stream
      .evalTap { case (_, peers) => logger.debug(show"Received peers $peers") }
      .flatMap { case (block, peers) =>
        Stream.emits(peers.map(peer => PeerAction.MessageToPeer(peer.id, NewBlock(block))).toSeq)
      }
  }
}
