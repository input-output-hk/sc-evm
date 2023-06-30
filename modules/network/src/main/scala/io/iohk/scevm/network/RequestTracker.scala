package io.iohk.scevm.network

import cats.Eq
import cats.effect.kernel.Temporal
import fs2.{Pipe2, Stream}
import io.iohk.scevm.network.p2p.messages.OBFT1.{RequestMessage, ResponseMessage}

import scala.concurrent.duration._

object RequestTracker {
  sealed trait Failure {
    val peerId: PeerId
    val request: RequestMessage
  }
  final case class Timeout(peerId: PeerId, request: RequestMessage)          extends Failure
  final case class PeerDisconnected(peerId: PeerId, request: RequestMessage) extends Failure

  final case class RequestStates(peerRequests: Map[PeerId, Set[RequestId]], failures: Seq[Failure])

  object RequestStates {
    def apply(state: State): RequestStates =
      RequestStates(state.byPeer.view.mapValues(_.keys.toSet).toMap, state.failed)
  }

  implicit val eqRequestStates: Eq[RequestStates] = Eq.fromUniversalEquals

  final case class RequestDetails(msg: RequestMessage, start: FiniteDuration)

  final case class State(byPeer: Map[PeerId, Map[RequestId, RequestDetails]], failed: Seq[Failure]) {
    def add(time: FiniteDuration, peerId: PeerId, msg: RequestMessage): State =
      copy(byPeer =
        byPeer.updatedWith(peerId)(ids =>
          Some(ids.getOrElse(Map.empty[RequestId, RequestDetails]) + (msg.requestId -> RequestDetails(msg, time)))
        )
      )

    def remove(peerId: PeerId, requestId: RequestId): State =
      copy(byPeer = byPeer.updatedWith(peerId)(ids => ids.map(_ - requestId).filterNot(_.isEmpty)))

    def disconnect(peerId: PeerId): State = {
      val expired = byPeer.getOrElse(peerId, Map.empty).values.toList
      if (expired.isEmpty)
        this
      else
        copy(
          byPeer = byPeer.removed(peerId),
          failed = expired.map(detail => PeerDisconnected(peerId, detail.msg))
        )
    }

    def checkTimeouts(threshold: FiniteDuration): State = {
      val timedOut = byPeer.toList
        .flatMap { case (peerId, requests) =>
          requests.values.collect {
            case RequestDetails(msg, time) if time < threshold => peerId -> msg
          }
        }

      timedOut
        .foldLeft(this) { case (state, (peerId, msg)) => state.remove(peerId, msg.requestId) }
        .copy(failed = timedOut.map { case (peerId, msg) => Timeout(peerId, msg) })
    }

    def reset: State = copy(failed = Nil)
  }

  object State {
    def empty: State = State(Map.empty, List.empty)
  }

  /** Track outgoing requests vs incoming responses to derive request states and
    * failures (due to peer disconnection and timeouts.)
    *
    * @param config sync config
    * @return pipe from peer actions & events to request states
    *         The first part of a request state is a map associating a PeerId to a set of RequestId
    *         corresponding to request that don't have response yet (and which are not in failure).
    *         The second part of a request state represents every requests that failed due to timeout or disconnection.
    */
  def pipe[F[_]: Temporal](
      config: SyncConfig
  ): Pipe2[F, PeerAction.MessageToPeer, PeerEvent, RequestStates] = { (peerActions, peerEvents) =>
    peerActions
      .merge(peerEvents)
      .mergeHaltL(Stream.awakeEvery(1.second))
      .scan(0.seconds -> State.empty) {
        case (time -> state, PeerAction.MessageToPeer(peerId, message: RequestMessage)) =>
          time -> state.reset.add(time, peerId, message)
        case (time -> state, PeerEvent.MessageFromPeer(peerId, message: ResponseMessage)) =>
          time -> state.reset.remove(peerId, message.requestId)
        case (time -> state, PeerEvent.PeerDisconnected(peerId)) =>
          time -> state.reset.disconnect(peerId)
        case (_ -> state, time: FiniteDuration) =>
          time -> state.reset.checkTimeouts(time - config.peerResponseTimeout)
        case (time -> state, _) =>
          time -> state.reset
      }
      .map { case _ -> state => RequestStates(state) }
  }
}
