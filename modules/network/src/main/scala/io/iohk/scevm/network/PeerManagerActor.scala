package io.iohk.scevm.network

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.util.Timeout
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.scevm.domain.NodeId
import io.iohk.scevm.network.PeerActor.Status.Handshaked
import io.iohk.scevm.network.PeerEventBus._
import io.iohk.scevm.network.handshaker.HandshakerState
import io.iohk.scevm.network.metrics.{BlockPropagationMetrics, NetworkMetrics}
import io.iohk.scevm.network.p2p.Message
import io.iohk.scevm.network.p2p.ProtocolVersions.Version
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlock
import io.iohk.scevm.network.p2p.messages.WireProtocol.Disconnect
import io.iohk.scevm.network.rlpx.AuthHandshaker
import io.iohk.scevm.network.stats.{PeerStat, PeerStatisticsActor}
import io.iohk.scevm.utils.AkkaCatsOps.CatsActorOps

import java.net.{InetSocketAddress, URI}
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

class PeerManagerActor(
    nodes: Set[Node],
    override val peerEventBus: PeerEventBus[IO],
    peerConfiguration: PeerConfig,
    peerStatistics: ActorRef,
    peerFactory: (ActorContext, InetSocketAddress, Boolean, Boolean) => ActorRef,
    externalSchedulerOpt: Option[Scheduler] = None,
    networkMetrics: NetworkMetrics[IO],
    blockPropagationMetrics: BlockPropagationMetrics[IO]
) extends Actor
    with ActorLogging
    with Stash
    with ActorPeerEventBusSupport {

  import PeerManagerActor._
  import akka.pattern.pipe

  var connectionAttempts: Int = 0

  implicit class ConnectedPeersOps(connectedPeers: ConnectedPeers) {

    /** Number of new connections the node should try to open at any given time. */
    def outgoingConnectionDemand: Int =
      PeerManagerActor.outgoingConnectionDemand(connectedPeers, peerConfiguration.connectionLimits)

    def canConnectTo(node: Node): Boolean =
      !connectedPeers.isConnectionHandled(node.tcpSocketAddress) &&
        !connectedPeers.hasHandshakedWith(node.id)
  }

  def scheduler: Scheduler                  = externalSchedulerOpt.getOrElse(context.system.scheduler)
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy() { case _ =>
      Stop
    }

  override def receive: Receive = {
    case StartConnecting =>
      scheduleNodesUpdate()
      context.become(listening(ConnectedPeers.empty))
      unstashAll()
    case _ =>
      stash()
  }

  private def scheduleNodesUpdate(): Unit =
    scheduler.scheduleWithFixedDelay(
      peerConfiguration.updateNodesInitialDelay,
      peerConfiguration.updateNodesInterval,
      self,
      ConnectToNodes
    )

  private def listening(connectedPeers: ConnectedPeers): Receive =
    handleCommonMessages(connectedPeers)
      .orElse(handleConnections(connectedPeers))
      .orElse(handleNewNodesToConnectMessages(connectedPeers))
      .orElse(handlePruning(connectedPeers))

  private def handleNewNodesToConnectMessages(connectedPeers: ConnectedPeers): Receive = { case ConnectToNodes =>
    maybeConnectToDiscoveredNodes(connectedPeers)
  }

  private def maybeConnectToDiscoveredNodes(connectedPeers: ConnectedPeers): Unit = {
    val nodesToConnect: Set[Node] = nodes.filter(connectedPeers.canConnectTo)

    (networkMetrics.discoveredPeersSize.set(nodes.size) >>
      networkMetrics.pendingPeersSize.set(connectedPeers.pendingPeersCount) >>
      networkMetrics.connectionAttempts.inc(nodesToConnect.size)).unsafeRunSync()

    val maxPeersTotal =
      peerConfiguration.connectionLimits.maxOutgoingPeers + peerConfiguration.connectionLimits.maxIncomingPeers
    log.info(
      s"Total number of known nodes: ${nodes.size}. " +
        s"Total number of connection attempts: $connectionAttempts. " +
        s"Handshaked peers: ${connectedPeers.handshakedPeersCount}/$maxPeersTotal, " +
        s"pending connection attempts: ${connectedPeers.pendingPeersCount}. " +
        s"Trying to connect to: ${nodesToConnect.size} more nodes."
    )

    if (nodesToConnect.nonEmpty) {
      log.debug("Trying to connect to {}", nodesToConnect)
      nodesToConnect.foreach { n =>
        connectionAttempts += 1
        self ! ConnectToPeer(n.toUri, isBootstrapPeer = true)
      }
    } else {
      log.debug("The nodes list is empty, no new nodes to connect to")
    }
  }

  private def handleConnections(connectedPeers: ConnectedPeers): Receive = {
    case HandlePeerConnection(connection, remoteAddress) =>
      handleConnection(connection, remoteAddress, connectedPeers)

    case ConnectToPeer(uri, isBootstrapPeer) =>
      connectWith(uri, isBootstrapPeer, connectedPeers)
  }

  private def handleConnection(
      connection: ActorRef,
      remoteAddress: InetSocketAddress,
      connectedPeers: ConnectedPeers
  ): Unit = {
    val alreadyConnectedToPeer = connectedPeers.isConnectionHandled(remoteAddress)
    val isPendingPeersNotMaxValue =
      connectedPeers.incomingPendingPeersCount < peerConfiguration.connectionLimits.maxPendingPeers

    val validConnection = for {
      validHandler <- validateConnection(
                        remoteAddress,
                        IncomingConnectionAlreadyHandled(remoteAddress, connection),
                        !alreadyConnectedToPeer
                      )
      validNumber <- validateConnection(
                       validHandler,
                       MaxIncomingPendingConnections(connection),
                       isPendingPeersNotMaxValue
                     )
    } yield validNumber

    validConnection match {
      case Right(address) =>
        val (peer, newConnectedPeers) =
          createPeer(address, incomingConnection = true, connectedPeers, isBootstrapPeer = false)
        peer.ref ! PeerActor.HandleConnection(connection, remoteAddress)
        context.become(listening(newConnectedPeers))

      case Left(error) =>
        handleConnectionErrors(error)
    }
  }

  private def connectWith(uri: URI, isBootstrapPeer: Boolean, connectedPeers: ConnectedPeers): Unit = {
    val nodeId        = NodeId.fromStringUnsafe(uri.getUserInfo)
    val remoteAddress = new InetSocketAddress(uri.getHost, uri.getPort)

    val alreadyConnectedToPeer =
      connectedPeers.hasHandshakedWith(nodeId) || connectedPeers.isConnectionHandled(remoteAddress)
    val isOutgoingPeersNotMaxValue =
      connectedPeers.outgoingPeersCount < peerConfiguration.connectionLimits.maxOutgoingPeers

    val validConnection = for {
      validHandler <- validateConnection(remoteAddress, OutgoingConnectionAlreadyHandled(uri), !alreadyConnectedToPeer)
      validNumber  <- validateConnection(validHandler, MaxOutgoingConnections, isOutgoingPeersNotMaxValue)
    } yield validNumber

    validConnection match {
      case Right(address) =>
        val (peer, newConnectedPeers) =
          createPeer(address, incomingConnection = false, connectedPeers, isBootstrapPeer = isBootstrapPeer)
        peer.ref ! PeerActor.ConnectTo(uri)
        context.become(listening(newConnectedPeers))

      case Left(error) => handleConnectionErrors(error)
    }
  }

  private def handleCommonMessages(connectedPeers: ConnectedPeers): Receive = {
    case SendMessage(NewBlock(block), peerId) if connectedPeers.getPeer(peerId).isDefined =>
      blockPropagationMetrics.blockToPeerActor
        .observe(IO.delay(connectedPeers.getPeer(peerId).get.ref ! PeerActor.SendMessage(NewBlock(block))))
        .unsafeRunSync()

    case SendMessage(message, peerId) if connectedPeers.getPeer(peerId).isDefined =>
      connectedPeers.getPeer(peerId).get.ref ! PeerActor.SendMessage(message)

    case Terminated(ref) =>
      val (terminatedPeersIds, newConnectedPeers) = connectedPeers.removeTerminatedPeer(ref)
      terminatedPeersIds.foreach { peerId =>
        peerEventBus.publish(PeerEvent.PeerDisconnected(peerId)).unsafeRunSync()
      }
      context.unwatch(ref)
      context.become(listening(newConnectedPeers))

    case PeerEvent.PeerHandshakeSuccessful(handshakedPeer, _) =>
      if (
        handshakedPeer.incomingConnection && connectedPeers.incomingHandshakedPeersCount >= peerConfiguration.connectionLimits.maxIncomingPeers
      ) {
        handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)

        // It looks like all incoming slots are taken; try to make some room.
        self ! SchedulePruneIncomingPeers

        context.become(listening(connectedPeers))

      } else if (handshakedPeer.nodeId.exists(connectedPeers.hasHandshakedWith)) {
        // FIXME: peers received after handshake should always have their nodeId defined, we could maybe later distinguish
        //        it into PendingPeer/HandshakedPeer classes

        // Even though we do already validations for this, we might have missed it someone tried connecting to us at the
        // same time as we do
        log.debug(s"Disconnecting from ${handshakedPeer.toShortString} as we are already connected to him")
        handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.AlreadyConnected)
      } else {
        context.become(listening(connectedPeers.promotePeerToHandshaked(handshakedPeer)))
      }
  }

  private def createPeer(
      address: InetSocketAddress,
      incomingConnection: Boolean,
      connectedPeers: ConnectedPeers,
      isBootstrapPeer: Boolean
  ): (Peer, ConnectedPeers) = {
    val ref = peerFactory(context, address, incomingConnection, isBootstrapPeer)
    context.watch(ref)

    // The peerId is unknown for a pending peer, hence it is created from the PeerActor's path.
    // Upon successful handshake, the pending peer is updated with the actual peerId derived from
    // the Node's public key. See: ConnectedPeers#promotePeerToHandshaked
    val pendingPeer =
      Peer(
        PeerId.fromRef(ref),
        address,
        ref,
        incomingConnection,
        nodeId = None,
        createTimeMillis = System.currentTimeMillis
      )

    val newConnectedPeers = connectedPeers.addNewPendingPeer(pendingPeer)

    (pendingPeer, newConnectedPeers)
  }

  private def handlePruning(connectedPeers: ConnectedPeers): Receive = {
    case SchedulePruneIncomingPeers =>
      import cats.effect.unsafe.implicits.global
      implicit val timeout: Timeout = Timeout(peerConfiguration.updateNodesInterval)

      // Ask for the whole statistics duration, we'll use averages to make it fair.
      val window = peerConfiguration.statSlotCount * peerConfiguration.statSlotDuration

      peerStatistics
        .askFor[IO, PeerStatisticsActor.StatsForAll](PeerStatisticsActor.GetStatsForAll(window))
        .map(PruneIncomingPeers)
        .unsafeToFuture()
        .pipeTo(self)

    case PruneIncomingPeers(PeerStatisticsActor.StatsForAll(stats)) =>
      val prunedConnectedPeers = pruneIncomingPeers(connectedPeers, stats)

      context.become(listening(prunedConnectedPeers))
  }

  /** Disconnect some incoming connections so we can free up slots. */
  private def pruneIncomingPeers(
      connectedPeers: ConnectedPeers,
      stats: Map[PeerId, PeerStat]
  ): ConnectedPeers = {
    val pruneCount =
      PeerManagerActor.numberOfIncomingConnectionsToPrune(connectedPeers, peerConfiguration.connectionLimits)
    val now = System.currentTimeMillis
    val (peersToPrune, prunedConnectedPeers) =
      connectedPeers.prunePeers(
        incoming = true,
        minAge = peerConfiguration.connectionLimits.minPruneAge,
        numPeers = pruneCount,
        priority = prunePriority(stats, now),
        currentTimeMillis = now
      )

    peersToPrune.foreach { peer =>
      peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)
    }

    prunedConnectedPeers
  }

  private def validateConnection(
      remoteAddress: InetSocketAddress,
      error: ConnectionError,
      stateCondition: Boolean
  ): Either[ConnectionError, InetSocketAddress] =
    Either.cond(stateCondition, remoteAddress, error)

  private def handleConnectionErrors(error: ConnectionError): Unit = error match {
    case MaxIncomingPendingConnections(connection) =>
      log.debug("Maximum number of pending incoming peers reached")
      connection ! PoisonPill

    case IncomingConnectionAlreadyHandled(remoteAddress, connection) =>
      log.debug("Another connection with {} is already opened. Disconnecting", remoteAddress)
      connection ! PoisonPill

    case MaxOutgoingConnections =>
      log.debug("Maximum number of connected peers reached")

    case OutgoingConnectionAlreadyHandled(uri) =>
      log.debug("Another connection with {} is already opened", uri)
  }

  override def initialFilter: Seq[SubscriptionClassifier] = Seq(SubscriptionClassifier.PeerHandshakedClassifier)
}

object PeerManagerActor {
  // scalastyle:off parameter.number
  def props(
      nodes: Set[URI],
      peerConfiguration: PeerConfig,
      peerEventBus: PeerEventBus[IO],
      peerStatistics: ActorRef,
      handshaker: HandshakerState[IO],
      authHandshaker: AuthHandshaker,
      bestProtocolVersion: Version,
      networkMetrics: NetworkMetrics[IO],
      blockPropagationMetrics: BlockPropagationMetrics[IO]
  ): Props = {
    val factory: (ActorContext, InetSocketAddress, Boolean, Boolean) => ActorRef =
      peerFactory(
        peerConfiguration,
        peerEventBus,
        handshaker,
        authHandshaker,
        bestProtocolVersion,
        blockPropagationMetrics
      )

    Props(
      new PeerManagerActor(
        NodeParser.parseNodes(nodes),
        peerEventBus,
        peerConfiguration,
        peerStatistics,
        peerFactory = factory,
        networkMetrics = networkMetrics,
        blockPropagationMetrics = blockPropagationMetrics
      )
    )
  }
  // scalastyle:on parameter.number

  def peerFactory(
      config: PeerConfig,
      eventBus: PeerEventBus[IO],
      handshaker: HandshakerState[IO],
      authHandshaker: AuthHandshaker,
      bestProtocolVersion: Version,
      blockPropagationMetrics: BlockPropagationMetrics[IO]
  ): (ActorContext, InetSocketAddress, Boolean, Boolean) => ActorRef = {
    (ctx, address, incomingConnection, isBootstrapPeer) =>
      val id: String = address.toString.filterNot(_ == '/')
      val props = PeerActor.props(
        address,
        config,
        eventBus,
        incomingConnection,
        isBootstrapPeer,
        handshaker,
        authHandshaker,
        bestProtocolVersion,
        blockPropagationMetrics
      )
      ctx.actorOf(props, id)
  }

  case object StartConnecting

  final case class HandlePeerConnection(connection: ActorRef, remoteAddress: InetSocketAddress)

  final case class ConnectToPeer(uri: URI, isBootstrapPeer: Boolean)

  case object ConnectToNodes

  final case class Peers(peers: Map[Peer, PeerActor.Status]) {
    def handshaked: Seq[Peer] = peers.collect { case (peer, Handshaked) => peer }.toSeq
  }

  final case class SendMessage(message: Message, peerId: PeerId)

  sealed abstract class ConnectionError

  final case class MaxIncomingPendingConnections(connection: ActorRef) extends ConnectionError

  final case class IncomingConnectionAlreadyHandled(address: InetSocketAddress, connection: ActorRef)
      extends ConnectionError

  case object MaxOutgoingConnections extends ConnectionError

  final case class OutgoingConnectionAlreadyHandled(uri: URI) extends ConnectionError

  final case class PeerAddress(value: String)

  case object SchedulePruneIncomingPeers
  final case class PruneIncomingPeers(stats: PeerStatisticsActor.StatsForAll)

  /** Number of new connections the node should try to open at any given time. */
  def outgoingConnectionDemand(
      connectedPeers: ConnectedPeers,
      peerConfiguration: PeerConfig.ConnectionLimits
  ): Int =
    if (connectedPeers.outgoingHandshakedPeersCount >= peerConfiguration.minOutgoingPeers)
      // We have established at least the minimum number of working connections.
      0
    else
      // Try to connect to more, up to the maximum, including pending peers.
      peerConfiguration.maxOutgoingPeers - connectedPeers.outgoingPeersCount

  def numberOfIncomingConnectionsToPrune(
      connectedPeers: ConnectedPeers,
      peerConfiguration: PeerConfig.ConnectionLimits
  ): Int = {
    val minIncomingPeers = peerConfiguration.maxIncomingPeers - peerConfiguration.pruneIncomingPeers
    math.max(
      0,
      connectedPeers.incomingHandshakedPeersCount - connectedPeers.incomingPruningPeersCount - minIncomingPeers
    )
  }

  /** Assign a priority to peers that we can use to order connections,
    * with lower priorities being the ones to prune first.
    */
  def prunePriority(stats: Map[PeerId, PeerStat], currentTimeMillis: Long)(peerId: PeerId): Double =
    stats
      .get(peerId)
      .flatMap { stat =>
        val maybeAgeSeconds = stat.firstSeenTimeMillis
          .map(currentTimeMillis - _)
          .map(_ * 1000)
          .filter(_ > 0)

        // Use the average number of responses per second over the lifetime of the connection
        // as an indicator of how fruitful the peer is for us.
        maybeAgeSeconds.map(age => stat.responsesReceived.toDouble / age)
      }
      .getOrElse(0.0)
}
