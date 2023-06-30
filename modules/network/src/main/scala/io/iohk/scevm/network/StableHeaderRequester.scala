package io.iohk.scevm.network

import cats.effect.Async
import fs2.Pipe
import io.iohk.scevm.metrics.Fs2Monitoring
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.GetStableHeaders
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StableHeaderRequester[F[_]: Async] {
  private val log: Logger[F] = Slf4jLogger.getLogger[F]

  def pipe(syncConfig: SyncConfig): Pipe[F, PeerEvent, PeerAction] =
    _.through(filterPeerId)
      .through(Fs2Monitoring.probe("stable_header_requester"))
      .through(processPeerId(syncConfig.scoringAncestorOffset))

  private def filterPeerId: Pipe[F, PeerEvent, PeerId] =
    _.collect {
      case PeerEvent.PeerHandshakeSuccessful(peer, _)           => peer.id
      case PeerEvent.MessageFromPeer(peerId, _: OBFT1.NewBlock) => peerId
    }

  private def processPeerId(ancestorOffset: Int): Pipe[F, PeerId, PeerAction] =
    _.evalTap(peerId => log.debug(s"Requesting StableHeaders for peer $peerId"))
      .map(PeerAction.MessageToPeer(_, GetStableHeaders(Seq(ancestorOffset))))
}

object StableHeaderRequester {
  def apply[F[_]: Async]: StableHeaderRequester[F] = new StableHeaderRequester[F]
}
