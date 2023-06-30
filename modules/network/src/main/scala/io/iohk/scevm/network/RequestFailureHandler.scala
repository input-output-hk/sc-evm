package io.iohk.scevm.network

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Sync
import cats.kernel.Order
import cats.syntax.all._
import fs2.concurrent.Signal
import fs2.{Pipe, Stream}
import io.iohk.scevm.network.RequestTracker.{Failure, PeerDisconnected, RequestStates}
import io.iohk.scevm.network.p2p.Message
import org.typelevel.log4cats.slf4j.Slf4jLogger

object RequestFailureHandler {

  final private case class BadScoredPeerId(failedRequests: Seq[Message], scoreByPeerId: Map[PeerId, Int])

  private object BadScoredPeerId {
    val Empty: BadScoredPeerId = BadScoredPeerId(Seq.empty, Map.empty)

    // Affect a score to each peer id associated to failed requests, with score = the number of failed requests
    def apply(failedRequests: NonEmptyList[Failure]): BadScoredPeerId =
      failedRequests.foldLeft(BadScoredPeerId.Empty) { case (acc, failure) =>
        BadScoredPeerId(
          acc.failedRequests :+ failure.request,
          acc.scoreByPeerId.updatedWith(failure.peerId)(old => Some(old.getOrElse(0) + 1))
        )
      }
  }

  final private case class RetriesTracker(state: Map[RequestId, Set[PeerId]]) {
    def update(requestsToRetry: Seq[Failure], requestsToDrop: Seq[Failure]): RetriesTracker = {
      val updatedState = requestsToRetry.foldLeft(state) { case (previousState, failure) =>
        previousState.updatedWith(failure.request.requestId)(maybeAlreadyRequestedPeerIds =>
          Some(maybeAlreadyRequestedPeerIds.getOrElse(Set.empty) + failure.peerId)
        )
      }

      // Removing requestIds is done after updating in order to make sure that they won't be added again
      val fullyUpdatedState = requestsToDrop.foldLeft(updatedState) { case (previousState, failure) =>
        previousState.removed(failure.request.requestId)
      }

      RetriesTracker(fullyUpdatedState)
    }
  }

  final private case class State(
      currentRequestsByPeer: Map[PeerId, Set[RequestId]],
      peerIds: Set[PeerId],
      retriesTracker: RetriesTracker,
      requestsToRetry: Seq[Failure],
      requestsToDrop: Seq[RequestId]
  ) {

    /** Update the state by updating the list of available peers and by removing requests that have failed for every peer.
      */
    def update(
        newCurrentRequestsByPeer: Map[PeerId, Set[RequestId]],
        newFailures: Seq[Failure],
        newPeerIds: Set[PeerId]
    ): State = {
      val disconnectedPeerIds = newFailures.collect { case PeerDisconnected(peerId, _) => peerId }
      val availablePeerIds    = newPeerIds -- disconnectedPeerIds

      val (requestsToRetry, requestsToDrop) = newFailures.partition { failure =>
        // Check if there is at least one peer that we didn't try to send the failed request
        (availablePeerIds -- retriesTracker.state.getOrElse(
          failure.request.requestId,
          Set.empty
        ) - failure.peerId).nonEmpty
      }

      State(
        newCurrentRequestsByPeer,
        availablePeerIds,
        retriesTracker.update(requestsToRetry, requestsToDrop),
        requestsToRetry,
        requestsToDrop.map(_.request.requestId)
      )
    }

    def toPeerActions[F[_]: Sync](myPeerId: PeerId): Stream[F, PeerAction.MessageToPeer] = {
      val logger = Slf4jLogger.getLogger

      NonEmptyList.fromList(requestsToRetry.toList) match {
        case Some(failedRequestsList) =>
          val badScore = BadScoredPeerId(failedRequestsList)

          NonEmptyList.fromList(peerIds.toList) match {
            case Some(notDisconnected) =>
              Stream.emits(generatePeerActions(myPeerId, notDisconnected.toNes(peerIdOrdering), badScore))
            case None =>
              Stream.empty.evalTap(_ =>
                logger.warn(
                  show"No peer available to retry requests. ${failedRequestsList.length} requests aborted."
                ) >> logger.debug(s"Aborted requests: ${failedRequestsList}")
              )
          }
        case _ => Stream.empty
      }
    }

    private def generatePeerActions[F[_]: Sync](
        myPeerId: PeerId,
        peerIds: NonEmptySet[PeerId],
        badScore: BadScoredPeerId
    ): List[PeerAction.MessageToPeer] = {
      val peerIdsWithScore =
        computeScore(peerIds, currentRequestsByPeer.view.mapValues(_.size).toMap, badScore.scoreByPeerId)

      generatePeerAction(myPeerId, badScore.failedRequests.toList, peerIds, peerIdsWithScore)
    }
  }

  private object State {
    val Empty: State = State(Map.empty, Set.empty, RetriesTracker(Map.empty), Seq.empty, Seq.empty)
  }

  private val peerIdOrdering: Order[PeerId] = Order.by(_.value)

  /** For each request that has failed previously (Timeout, PeerDisconnected, ...), generates a PeerAction
    * if there are still some peers that haven't try to process the request.
    *
    * If a request has been sent to every peer, and no peer was able to send a response in time,
    * then the request is cancelled (no more retry).
    */
  def regeneratePeerAction[F[_]: Sync](
      myPeerId: PeerId,
      peerIdsSignal: Signal[F, Set[PeerId]]
  ): Pipe[F, RequestStates, PeerAction.MessageToPeer] = { requestStates =>
    val logger = Slf4jLogger.getLogger

    requestStates
      .zip(peerIdsSignal.continuous)
      .scan(State.Empty) { case (state, (RequestStates(currentRequestsByPeer, failures), peerIds)) =>
        state.update(currentRequestsByPeer, failures, peerIds)
      }
      .evalTap { case State(_, _, _, _, requestsToDrop) =>
        requestsToDrop.traverse(request =>
          logger.warn(s"No peer was able to respond in time to $request, so it is cancelled.")
        )
      }
      .flatMap(_.toPeerActions(myPeerId))
      .evalTap(peerAction => logger.debug(s"Resent failed request: $peerAction"))
  }

  /** Associates each peer to a "busy" score.
    * Peers with the lowest score are less busy than those with high score.
    *
    * The goal is to select the peer with the least in flight request among those without failed request,
    * and fall back to peers with failed request otherwise.
    *
    * The score of a peer will be equal to the number of requests that it's already handling + the number of requests
    * that have already failed and that are related to this peer.
    *
    * Example:
    * - FAILED_REQUEST_MALUS = 1000
    * - Peer1 has no request to handle and it is not a bad peer => score = 0
    * - Peer2 has already received 2 requests, and it is not a bad peer => score = 2
    * - Peer3 has already received 3 requests, and it is not a bad peer => score = 3
    * - Peer4 has already received 1 request, and it is a bad peer (4 failed requests) => score = 4001
    * - Peer5 has no request to handle and it is a bad peer (5 failed requests) => score = 5000
    *
    * @param peerIds the set of available peer's id
    * @param peerActivities a Map associating a peer id to the number of requests that are sent to it and we didn't receive the associated responses
    * @param badPeers a Map associating a peer's id to the number of requests that were sent to it, and that has failed (due to timeout or disconnection)
    * @return each peer Id with a score
    */
  private def computeScore(
      peerIds: NonEmptySet[PeerId],
      peerActivities: Map[PeerId, Int],
      badPeers: Map[PeerId, Int]
  ): Map[PeerId, Int] = {
    val FAILED_REQUEST_MALUS = 1000
    peerIds.toList.map { peerId =>
      val score = peerActivities.getOrElse(peerId, 0) + badPeers.getOrElse(peerId, 0) * FAILED_REQUEST_MALUS
      peerId -> score
    }.toMap
  }

  /** Generates a PeerAction for each failed requests, using the best peer available
    * @param messages a list of failed messages
    * @param peerIds a list of available peerId
    * @param peerIdsWithScore a Map that associate each peer Id to a "busy" score
    * @param myPeerId the PeerId associating to the node currently running
    * @return a list of PeerAction
    */
  private def generatePeerAction[F](
      myPeerId: PeerId,
      messages: List[Message],
      peerIds: NonEmptySet[PeerId],
      peerIdsWithScore: Map[PeerId, Int]
  ): List[PeerAction.MessageToPeer] =
    messages
      .foldRight((List.empty[PeerAction.MessageToPeer], peerIdsWithScore)) { case (message, (peerActions, scores)) =>
        val peerId                  = getLessBusyPeer(peerIds, scores, myPeerId)
        val peerAction              = PeerAction.MessageToPeer(peerId, message)
        val updatedPeerIdsWithScore = scores.updatedWith(peerId)(_.map(_ + 1))
        (peerAction :: peerActions, updatedPeerIdsWithScore)
      }
      ._1

  /** Retrieves the less busy peer according to a "busy" score Map.
    *
    * If there are multiple peers with the same score, the peer with the closest kademlia distance is chosen.
    * And if there are still multiple concurrent peers, then the peer with the lowest id (alphanumerical) is chosen.
    *
    * The idea behind is to provide a deterministic way to find the peer,
    * and to spread request over the network without always choosing the same peer.
    *
    * @param peerIds a list of available peerId
    * @param peerIdsWithScore a Map that associate each peer Id to a "busy" score
    * @param myPeerId the PeerId associating to the node currently running
    * @return the less busy peer
    */
  private def getLessBusyPeer(
      peerIds: NonEmptySet[PeerId],
      peerIdsWithScore: Map[PeerId, Int],
      myPeerId: PeerId
  ): PeerId =
    if (peerIds.size == 1) {
      peerIds.head
    } else {
      implicit val implicitPeerIdOrdering: Order[PeerId] = peerIdOrdering
      peerIds
        .minimumBy(peerId => peerId -> peerIdsWithScore.getOrElse(peerId, 0))(
          Order.by { case (peerId, score) => (score, myPeerId.kademliaDistance(peerId), peerId) }
        )
    }
}
