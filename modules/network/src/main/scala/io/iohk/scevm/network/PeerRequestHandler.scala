package io.iohk.scevm.network

import akka.actor._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.scevm.network.PeerEvent.{MessageFromPeer, PeerDisconnected}
import io.iohk.scevm.network.PeerEventBus.PeerSelector
import io.iohk.scevm.network.PeerEventBus.SubscriptionClassifier.{PeerDisconnectedClassifier, ResponseClassifier}
import io.iohk.scevm.network.p2p.Message
import io.iohk.scevm.network.p2p.messages.OBFT1.RequestMessage

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

class PeerRequestHandler[RequestMsg <: RequestMessage, ResponseMsg <: Message: ClassTag](
    peer: Peer,
    responseTimeout: FiniteDuration,
    peerEventBus: PeerEventBus[IO],
    sendPeerActions: PeerAction => IO[Unit],
    requestMsg: RequestMsg
)(implicit toSerializable: RequestMsg => Message)
    extends Actor
    with ActorLogging {

  import PeerRequestHandler._

  private val initiator: ActorRef = context.parent

  private val startTime: Long = System.currentTimeMillis()

  private def subscribeMessageClassifier = ResponseClassifier(PeerSelector.WithId(peer.id), requestMsg.requestId)

  private def timeTakenSoFar(): Long = System.currentTimeMillis() - startTime

  override def preStart(): Unit =
    subscribeToEventStream().unsafeRunSync()

  override def receive: Receive = {
    case MessageFromPeer(_, responseMsg: ResponseMsg)  => handleResponseMsg(responseMsg)
    case Timeout                                       => handleTimeout()
    case PeerDisconnected(peerId) if peerId == peer.id => handleTerminated()
  }

  def handleResponseMsg(responseMsg: ResponseMsg): Unit = {
    cleanupAndStop()
    initiator ! ResponseReceived(peer, responseMsg, timeTaken = timeTakenSoFar())
  }

  def handleTimeout(): Unit = {
    cleanupAndStop()
    initiator ! RequestFailed(peer, requestMsg.requestId, "request timeout")
  }

  def handleTerminated(): Unit = {
    cleanupAndStop()
    initiator ! RequestFailed(peer, requestMsg.requestId, "connection closed")
  }

  def cleanupAndStop(): Unit =
    context.stop(self)

  private def subscribeToEventStream(): IO[Unit] =
    (for {
      subscription <-
        fs2.Stream
          .resource(
            peerEventBus.subscribe(subscribeMessageClassifier, PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)))
          )
          .evalTap(_ => sendPeerActions(PeerAction.MessageToPeer(peer.id, toSerializable(requestMsg))))
      _ <- subscription.stream
             .timeout(responseTimeout)
             .take(1)
             .evalTap(event => IO.delay(self ! event))
             .handleErrorWith {
               case _: TimeoutException => fs2.Stream.eval(IO.delay(self ! Timeout))
               case other               => fs2.Stream.raiseError[IO](other)
             }
    } yield ()).compile.drain
      .onError(e => IO(log.error(e, "Unexpected error during peer request subscription.")))
      .start
      .void

}

object PeerRequestHandler {
  def props[ResponseMsg <: Message: ClassTag](
      peer: Peer,
      responseTimeout: FiniteDuration,
      peerEventBus: PeerEventBus[IO],
      sendPeerActions: PeerAction => IO[Unit],
      requestMsg: RequestMessage
  ): Props =
    Props(new PeerRequestHandler(peer, responseTimeout, peerEventBus, sendPeerActions, requestMsg))

  final case class RequestFailed(peer: Peer, requestId: RequestId, reason: String)
  final case class ResponseReceived[T](peer: Peer, response: T, timeTaken: Long)

  private case object Timeout
}
