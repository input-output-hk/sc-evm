package io.iohk.scevm.network

import akka.actor.ActorRef
import cats.effect.Async
import fs2.concurrent.{Signal, Topic}
import fs2.{Pipe, Stream}
import io.iohk.scevm.metrics.Fs2Monitoring
import org.typelevel.log4cats.LoggerFactory

object PeerEventProcessingStream {

  def getStream[F[_]: Async: LoggerFactory](
      syncConfig: SyncConfig,
      peerEventsTopic: Topic[F, PeerEvent],
      peerManagerActor: ActorRef,
      processingPipes: Seq[Pipe[F, PeerEvent, PeerAction]],
      peersStateRef: Signal[F, Map[PeerId, PeerWithInfo]],
      myPeerId: PeerId
  ): Stream[F, Unit] = {
    val logger = LoggerFactory[F].getLogger

    val peerEvents = peerEventsTopic.subscribe(syncConfig.messageSourceBuffer)
    peerEvents
      .through(Fs2Monitoring.probe("message_received"))
      .evalTap(peerEvent => logger.trace(s"input peerEvent=$peerEvent"))
      .broadcastThrough(processingPipes: _*)
      .evalTap(peerAction => logger.trace(s"output $peerAction"))
      .through(PeerAction.messagesToConnectedPeers(peersStateRef))
      .broadcastThrough[F, PeerAction.MessageToPeer](
        identity,
        _.through2(peerEvents)(RequestTracker.pipe(syncConfig))
          .through(RequestFailureHandler.regeneratePeerAction(myPeerId, peersStateRef.map(_.keySet)))
      )
      .evalTap { case PeerAction.MessageToPeer(peerId, message) =>
        Async[F].delay(peerManagerActor ! PeerManagerActor.SendMessage(message, peerId))
      }
      .drain
  }
}
