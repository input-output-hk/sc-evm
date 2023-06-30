package io.iohk.scevm.network.stats

import akka.actor._
import cats.effect.IO
import io.iohk.scevm.network.PeerEventBus.{PeerSelector, SubscriptionClassifier}
import io.iohk.scevm.network.p2p.{Codes, Message}
import io.iohk.scevm.network.{ActorPeerEventBusSupport, PeerEvent, PeerEventBus, PeerId}

import java.time.Clock
import scala.concurrent.duration.FiniteDuration

class PeerStatisticsActor(
    override val peerEventBus: PeerEventBus[IO],
    private var maybeStats: Option[TimeSlotStats[PeerId, PeerStat]]
)(implicit clock: Clock)
    extends Actor
    with ActorPeerEventBusSupport {
  import PeerStatisticsActor._

  def receive: Receive = handlePeerEvents.orElse(handleStatsRequests)

  private def handlePeerEvents: Receive = {
    case PeerEvent.MessageFromPeer(peerId, msg) =>
      handleMessageFromPeer(peerId, msg)

    case PeerEvent.PeerDisconnected(peerId) =>
      maybeStats = maybeStats.map(_.remove(peerId))
  }

  private def handleStatsRequests: Receive = {
    case GetStatsForAll(window) =>
      val stats = maybeStats.map(_.getAll(Some(window))).getOrElse(Map.empty)
      sender() ! StatsForAll(stats)

    case GetStatsForPeer(window, peerId) =>
      val stats = maybeStats.map(_.get(peerId, Some(window))).getOrElse(PeerStat.empty)
      sender() ! StatsForPeer(peerId, stats)
  }

  private def handleMessageFromPeer(peerId: PeerId, msg: Message): Unit = {
    val now = clock.millis
    val obs = PeerStat(
      responsesReceived = if (ResponseCodes(msg.code)) 1 else 0,
      requestsReceived = if (RequestCodes(msg.code)) 1 else 0,
      firstSeenTimeMillis = Some(now),
      lastSeenTimeMillis = Some(now)
    )
    maybeStats = maybeStats.map(_.add(peerId, obs))
  }

  override protected def initialFilter: Seq[SubscriptionClassifier] = Seq(MessageSubscriptionClassifier)
}

object PeerStatisticsActor {
  def props(peerEventBus: PeerEventBus[IO], slotDuration: FiniteDuration, slotCount: Int)(implicit
      clock: Clock
  ): Props =
    Props {
      val stats = TimeSlotStats[PeerId, PeerStat](slotDuration, slotCount)
      new PeerStatisticsActor(peerEventBus, stats)
    }

  final case class GetStatsForAll(window: FiniteDuration)
  final case class StatsForAll(stats: Map[PeerId, PeerStat])
  final case class GetStatsForPeer(window: FiniteDuration, peerId: PeerId)
  final case class StatsForPeer(peerId: PeerId, stat: PeerStat)

  val ResponseCodes: Set[Int] = Set(
    Codes.NewBlockCode,
    Codes.NewBlockHashesCode,
    Codes.SignedTransactionsCode,
    Codes.BlockHeadersCode,
    Codes.BlockBodiesCode,
    Codes.NodeDataCode,
    Codes.ReceiptsCode
  )

  val RequestCodes: Set[Int] = Set(
    Codes.GetBlockHeadersCode,
    Codes.GetBlockBodiesCode,
    Codes.GetNodeDataCode,
    Codes.GetReceiptsCode
  )

  private val MessageSubscriptionClassifier: SubscriptionClassifier.MessageClassifier =
    SubscriptionClassifier.MessageClassifier(
      messageCodes = RequestCodes.union(ResponseCodes),
      peerSelector = PeerSelector.AllPeers
    )
}
