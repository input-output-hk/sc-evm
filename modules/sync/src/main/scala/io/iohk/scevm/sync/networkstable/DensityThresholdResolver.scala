package io.iohk.scevm.sync.networkstable

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all._
import fs2.concurrent.Signal
import io.iohk.scevm.network.domain.{ChainTooShort, StableHeaderScore}
import io.iohk.scevm.network.{PeerId, PeerInfo, PeerWithInfo}
import io.iohk.scevm.sync.networkstable.NetworkStableHeaderResult.{
  NetworkChainTooShort,
  NoAvailablePeer,
  StableHeaderFound,
  StableHeaderNotFound
}
import org.typelevel.log4cats.SelfAwareStructuredLogger

class DensityThresholdResolver[F[_]: Monad](
    logger: SelfAwareStructuredLogger[F],
    minimumThreshold: Double,
    stableHeaderOrdering: Ordering[StableHeaderFound]
) extends NetworkStableHeaderResolver[F] {

  /** Resolve a network stable header by:
    * - filter out peers with a score lower than the threshold
    * - sort the remaining headers by a given criteria
    * - returns the highest ranking element from the matching results
    */
  override def resolve(peersWithInfo: Signal[F, Map[PeerId, PeerWithInfo]]): F[NetworkStableHeaderResult] =
    for {
      peersMap <- peersWithInfo.get
      _        <- logPeerScores(logger, peersMap)
      result <- if (peersMap.isEmpty) {
                  logger.debug("No peers available") >>
                    Monad[F].pure(NoAvailablePeer)
                } else if (areAllPeersWithShortChain(peersMap)) {
                  logger.debug("All connected peers present a chain that is too short for density computation") >>
                    Monad[F].pure(NetworkChainTooShort)
                } else {
                  getStableHeaderScore(peersMap, minimumThreshold, stableHeaderOrdering) match {
                    case Some(stableHeaderFound @ StableHeaderFound(header, score, peerIds)) =>
                      logger.debug(
                        show"Found header $header with score $score and ${peerIds.size} peers: ${peerIds.toList.mkString(", ")}"
                      ) >>
                        Monad[F].pure(stableHeaderFound)
                    case None =>
                      logger.debug(s"Unable to find network stable header with a minimum score of $minimumThreshold") >>
                        Monad[F].pure(StableHeaderNotFound(minimumThreshold))

                  }
                }
    } yield result

  private def logPeerScores(
      logger: SelfAwareStructuredLogger[F],
      peersWithInfo: Map[PeerId, PeerWithInfo]
  ): F[Unit] = {
    val peerId2Score = peersWithInfo.flatMap { case (peerId, peerWithInfo) =>
      peerWithInfo.peerInfo.peerBranchScore.map(score => peerId -> score)
    }

    logger.isDebugEnabled.flatMap { debugEnabled =>
      val connectedPeersDescription =
        if (peerId2Score.isEmpty) "but the node is not connected to any peer"
        else if (debugEnabled) show"from ${peerId2Score.mkString(", ")}"
        else show"from ${peerId2Score.keys.size} connected peers"

      logger.info(
        show"Searching for the network's stable header $connectedPeersDescription"
      )
    }
  }

  /** Check that all peers' chains are too short for meaningful scoring.
    */
  private def areAllPeersWithShortChain(peersMap: Map[PeerId, PeerWithInfo]): Boolean =
    peersMap.nonEmpty && peersMap.values.forall {
      case PeerWithInfo(_, PeerInfo(Some(ChainTooShort(_)))) => true
      case _                                                 => false
    }

  /** Find the network stable that:
    * - has a score higher or equal to the minimum threshold
    * - is the highest sorted result according to the ordering parameter
    */
  private def getStableHeaderScore(
      peersMap: Map[PeerId, PeerWithInfo],
      minimumThreshold: Double,
      ordering: Ordering[StableHeaderFound]
  ): Option[StableHeaderFound] =
    peersMap
      .collect {
        case (peerId, PeerWithInfo(_, PeerInfo(Some(score: StableHeaderScore)))) if score.score >= minimumThreshold =>
          (peerId, score)
      }
      .toList
      .groupMap { case (_, stableHeaderScore) => stableHeaderScore } { case (peerId, _) => peerId }
      .map { case (score, peerIdList) => (score, NonEmptyList.fromList(peerIdList)) }
      .collect { case (score, Some(peerIdNEL)) => StableHeaderFound(score.stable, score.score, peerIdNEL) }
      .maxOption(ordering)
}
