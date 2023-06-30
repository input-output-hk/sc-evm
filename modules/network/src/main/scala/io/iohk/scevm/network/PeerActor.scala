package io.iohk.scevm.network

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.scevm.domain.NodeId
import io.iohk.scevm.network.PeerActor.Status._
import io.iohk.scevm.network.PeerEvent.{MessageFromPeer, PeerHandshakeSuccessful}
import io.iohk.scevm.network.handshaker._
import io.iohk.scevm.network.metrics.BlockPropagationMetrics
import io.iohk.scevm.network.p2p.ProtocolVersions.Version
import io.iohk.scevm.network.p2p._
import io.iohk.scevm.network.p2p.messages.WireProtocol._
import io.iohk.scevm.network.rlpx.{AuthHandshaker, RLPxConnectionHandler}
import io.iohk.scevm.utils.Logger

import java.net.{InetSocketAddress, URI}

/** Peer actor is responsible for initiating and handling high-level connection with peer.
  * It creates child RLPxConnectionActor for handling underlying RLPx communication.
  * Once RLPx connection is established it proceeds with protocol handshake (i.e `Hello`
  * and `Status` exchange).
  * Once that's done it can send/receive messages with peer (HandshakedHandler.receive).
  */
class PeerActor(
    peerAddress: InetSocketAddress,
    rlpxConnectionFactory: ActorContext => ActorRef,
    val peerConfiguration: PeerConfig,
    peerEventBus: PeerEventBus[IO],
    incomingConnection: Boolean,
    isBootstrapPeer: Boolean,
    externalSchedulerOpt: Option[Scheduler] = None,
    initHandshaker: HandshakerState[IO]
) extends Actor
    with ActorLogging
    with Stash {

  import PeerActor._
  import context.{dispatcher, system}

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy() { case _ =>
      Escalate
    }

  def scheduler: Scheduler = externalSchedulerOpt.getOrElse(system.scheduler)

  override def receive: Receive = waitingForInitialCommand

  def waitingForInitialCommand: Receive = stashMessages.orElse {
    case HandleConnection(connection, remoteAddress) =>
      val rlpxConnection = createRlpxConnection(remoteAddress, None)
      rlpxConnection.ref ! RLPxConnectionHandler.HandleConnection(connection)
      context.become(waitingForConnectionResult(rlpxConnection))

    case ConnectTo(uri) =>
      val rlpxConnection = createRlpxConnection(new InetSocketAddress(uri.getHost, uri.getPort), Some(uri))
      rlpxConnection.ref ! RLPxConnectionHandler.ConnectTo(uri)
      context.become(waitingForConnectionResult(rlpxConnection))
  }

  def createRlpxConnection(remoteAddress: InetSocketAddress, uriOpt: Option[URI]): RLPxConnection = {
    val ref = rlpxConnectionFactory(context)
    context.watch(ref)
    RLPxConnection(ref, remoteAddress, uriOpt)
  }

  private def modifyOutGoingUri(remoteNodeId: NodeId, rlpxConnection: RLPxConnection, uri: URI): URI = {
    val host  = getHostName(rlpxConnection.remoteAddress.getAddress)
    val port  = rlpxConnection.remoteAddress.getPort
    val query = Option(uri.getQuery).getOrElse(s"discport=$port")
    new URI(s"enode://${remoteNodeId.toHex}@$host:$port?$query")
  }

  def waitingForConnectionResult(rlpxConnection: RLPxConnection, numRetries: Int = 0): Receive =
    handleTerminated(rlpxConnection, numRetries, Connecting).orElse(stashMessages).orElse {
      case RLPxConnectionHandler.ConnectionEstablished(remoteNodeId) =>
        val newUri =
          rlpxConnection.uriOpt.map(outGoingUri => modifyOutGoingUri(remoteNodeId, rlpxConnection, outGoingUri))
        processHandshakerNextMessage(initHandshaker, remoteNodeId, rlpxConnection.copy(uriOpt = newUri), numRetries)

      case RLPxConnectionHandler.ConnectionFailed =>
        log.warning(s"Failed to establish RLPx connection ${rlpxConnection.remoteAddress}")
        rlpxConnection.uriOpt match {
          case Some(uri) if numRetries < peerConfiguration.connectMaxRetries =>
            context.unwatch(rlpxConnection.ref)
            scheduleConnectRetry(uri, numRetries)
          case Some(_) =>
            context.stop(self)
          case None =>
            log.debug("Connection was initiated by remote peer, not attempting to reconnect")
            context.stop(self)
        }
    }

  def processingHandshaking(
      handshaker: HandshakerState[IO],
      remoteNodeId: NodeId,
      rlpxConnection: RLPxConnection,
      timeout: Cancellable,
      numRetries: Int
  ): Receive =
    handleTerminated(rlpxConnection, numRetries, Handshaking(numRetries))
      .orElse(handleDisconnectMsg(rlpxConnection, Handshaking(numRetries)))
      .orElse(handlePingMsg(rlpxConnection))
      .orElse(stashMessages)
      .orElse {

        case RLPxConnectionHandler.MessageReceived(msg) =>
          // Processes the received message, cancels the timeout and processes a new message but only if the handshaker
          // handles the received message
          log.debug("Message received: {} from peer {}", msg, peerAddress)
          handshaker.applyMessage(msg, isBootstrapPeer).foreach { newHandshaker =>
            timeout.cancel()
            processHandshakerNextMessage(newHandshaker, remoteNodeId, rlpxConnection, numRetries)
          }

        case ResponseTimeout =>
          timeout.cancel()
          val newHandshaker = handshaker.processTimeout
          processHandshakerNextMessage(newHandshaker, remoteNodeId, rlpxConnection, numRetries)
      }

  /** Asks for the next message to send to the handshaker, or, if there is None,
    * becomes MessageHandler if handshake was successful or disconnects from the peer otherwise
    *
    * @param handshaker
    * @param rlpxConnection
    * @param numRetries , number of connection retries done during RLPxConnection establishment
    */
  private def processHandshakerNextMessage(
      handshaker: HandshakerState[IO],
      remoteNodeId: NodeId,
      rlpxConnection: RLPxConnection,
      numRetries: Int
  ): Unit =
    handshaker match {
      case HandshakeInProgress(NextMessage(msgToSend, timeoutTime)) =>
        import cats.effect.unsafe.implicits.global // FIXME don't use unsafe runtime
        (for {
          msg       <- msgToSend: IO[Message]
          _         <- IO(rlpxConnection.sendMessage(msg))
          newTimeout = scheduler.scheduleOnce(timeoutTime, self, ResponseTimeout)
          _ <- IO(
                 context.become(processingHandshaking(handshaker, remoteNodeId, rlpxConnection, newTimeout, numRetries))
               )
        } yield ())
          .unsafeRunSync()

      case HandshakeSuccess(handshakeResult) =>
        context.become(new HandshakedPeer(remoteNodeId, rlpxConnection, handshakeResult).receive)
        unstashAll()

      case HandshakeFailure(reason) =>
        disconnectFromPeer(rlpxConnection, reason)

    }

  private def scheduleConnectRetry(uri: URI, numRetries: Int): Unit = {
    log.debug("Scheduling connection retry in {}", peerConfiguration.connectRetryDelay)
    scheduler.scheduleOnce(peerConfiguration.connectRetryDelay, self, RetryConnectionTimeout)
    context.become { case RetryConnectionTimeout =>
      reconnect(uri, numRetries + 1)
    }
  }

  private def disconnectFromPeer(rlpxConnection: RLPxConnection, reason: Int): Unit = {
    rlpxConnection.sendMessage(Disconnect(reason))
    scheduler.scheduleOnce(peerConfiguration.disconnectPoisonPillTimeout, self, PoisonPill)
    context.unwatch(rlpxConnection.ref)
    context.become(disconnected)
  }

  private def stopActor(rlpxConnection: RLPxConnection, status: Status): Unit = status match {
    case Handshaked => gracefulStop(rlpxConnection)
    case _          => context.stop(self)
  }

  private def gracefulStop(rlpxConnection: RLPxConnection): Unit = {
    scheduler.scheduleOnce(peerConfiguration.disconnectPoisonPillTimeout, self, PoisonPill)
    context.unwatch(rlpxConnection.ref)
    context.become(disconnected)
  }

  def disconnected: Receive = PartialFunction.empty

  def handleTerminated(rlpxConnection: RLPxConnection, numRetries: Int, status: Status): Receive = {
    case Terminated(actor) if actor == rlpxConnection.ref =>
      rlpxConnection.uriOpt.foreach(uri => log.debug(s"Underlying rlpx connection with peer ${uri.getUserInfo} closed"))
      rlpxConnection.uriOpt match {
        case Some(uri) if numRetries < peerConfiguration.connectMaxRetries => scheduleConnectRetry(uri, numRetries + 1)
        case _                                                             => stopActor(rlpxConnection, status)
      }
  }

  def reconnect(uri: URI, numRetries: Int): Unit = {
    log.warning(s"Trying to reconnect ${uri.toString} (number of retries: $numRetries)")
    val address       = new InetSocketAddress(uri.getHost, uri.getPort)
    val newConnection = createRlpxConnection(address, Some(uri))
    newConnection.ref ! RLPxConnectionHandler.ConnectTo(uri)
    context.become(waitingForConnectionResult(newConnection, numRetries))
  }

  def handlePingMsg(rlpxConnection: RLPxConnection): Receive = { case RLPxConnectionHandler.MessageReceived(_: Ping) =>
    rlpxConnection.sendMessage(Pong())
  }

  def handleDisconnectMsg(rlpxConnection: RLPxConnection, status: Status): Receive = {
    case RLPxConnectionHandler.MessageReceived(d: Disconnect) =>
      log.debug(s"Received {}. Closing connection with peer ${peerAddress.getHostString}:${peerAddress.getPort}", d)
      stopActor(rlpxConnection, status)
  }

  def stashMessages: Receive = { case _: SendMessage | _: DisconnectPeer =>
    stash()
  }

  // The actor logs incoming messages, which can be quite verbose even for DEBUG mode.
  // ActorLogging doesn't support TRACE, but we can push more details if trace is enabled using the normal logging facilites.
  object MessageLogger extends Logger {
    val isTraceEnabled: Boolean = {
      var enabled = false
      log.whenTraceEnabled { enabled = true }
      enabled
    }
    def logMessage(peerId: PeerId, message: Message): Unit =
      // Sometimes potentially seeing the full block in the result is useful.
      if (isTraceEnabled) {
        log.trace(s"Received message: {} from $peerId", message)
      } else {
        log.debug(s"Received message: {} from $peerId", message.toShortString)
      }
  }

  class HandshakedPeer(remoteNodeId: NodeId, rlpxConnection: RLPxConnection, handshakeResult: PeerInfo) {

    val peerId: PeerId = PeerId(remoteNodeId.toHex)
    val peer: Peer     = Peer(peerId, peerAddress, self, incomingConnection, Some(remoteNodeId))
    peerEventBus.publish(PeerHandshakeSuccessful(peer, handshakeResult)).unsafeRunSync()

    /** main behavior of actor that handles peer communication and subscriptions for messages
      */
    def receive: Receive =
      handlePingMsg(rlpxConnection)
        .orElse(handleDisconnectMsg(rlpxConnection, Handshaked))
        .orElse(handleTerminated(rlpxConnection, 0, Handshaked))
        .orElse {

          case RLPxConnectionHandler.MessageReceived(message) =>
            MessageLogger.logMessage(peerId, message)
            peerEventBus.publish(MessageFromPeer(peerId, message)).unsafeRunSync()

          case DisconnectPeer(reason) =>
            disconnectFromPeer(rlpxConnection, reason)

          case SendMessage(message) =>
            rlpxConnection.sendMessage(message)
        }
  }

}

object PeerActor {
  //scalastyle:off parameter.number
  def props(
      peerAddress: InetSocketAddress,
      peerConfiguration: PeerConfig,
      peerEventBus: PeerEventBus[IO],
      incomingConnection: Boolean,
      isBootstrapPeer: Boolean,
      handshaker: HandshakerState[IO],
      authHandshaker: AuthHandshaker,
      bestProtocolVersion: Version,
      blockPropagationMetrics: BlockPropagationMetrics[IO]
  ): Props =
    Props(
      new PeerActor(
        peerAddress,
        rlpxConnectionFactory(
          authHandshaker,
          peerConfiguration.rlpxConfiguration,
          bestProtocolVersion,
          blockPropagationMetrics
        ),
        peerConfiguration,
        peerEventBus,
        incomingConnection,
        isBootstrapPeer,
        initHandshaker = handshaker
      )
    )
  //scalastyle:on
  def rlpxConnectionFactory(
      authHandshaker: AuthHandshaker,
      rlpxConfiguration: RLPxConfig,
      bestProtocolVersion: Version,
      blockPropagationMetrics: BlockPropagationMetrics[cats.effect.IO]
  ): ActorContext => ActorRef = { ctx =>
    ctx.actorOf(
      RLPxConnectionHandler
        .props(bestProtocolVersion, authHandshaker, rlpxConfiguration, blockPropagationMetrics),
      "rlpx-connection"
    )
  }

  final case class RLPxConnection(ref: ActorRef, remoteAddress: InetSocketAddress, uriOpt: Option[URI]) {
    def sendMessage(message: Message): Unit =
      ref ! RLPxConnectionHandler.SendMessage(message)
  }

  final case class HandleConnection(connection: ActorRef, remoteAddress: InetSocketAddress)

  final case class IncomingConnectionHandshakeSuccess(peer: Peer)

  final case class ConnectTo(uri: URI)

  final case class SendMessage(message: Message)

  private case object RetryConnectionTimeout

  private case object ResponseTimeout

  final case class DisconnectPeer(reason: Int)

  sealed trait Status

  object Status {

    case object Idle extends Status

    case object Connecting extends Status

    final case class Handshaking(numRetries: Int) extends Status

    case object Handshaked extends Status

    case object Disconnected extends Status

  }

}
