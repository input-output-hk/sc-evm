package io.iohk.scevm.network.handshaker

import cats.Show
import cats.data.{Validated, ValidatedNel}
import cats.effect.IO
import cats.implicits.{showInterpolator, toTraverseOps}
import io.iohk.bytes.showForByteString
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.network.forkid.{Connect, ForkId, ForkIdValidator}
import io.iohk.scevm.network.p2p.Capability.Capabilities.OBFT1
import io.iohk.scevm.network.p2p.messages.WireProtocol.Disconnect.Reasons
import io.iohk.scevm.network.p2p.messages.WireProtocol.{Disconnect, Hello}
import io.iohk.scevm.network.p2p.messages.{OBFT1 => OBTF1Messages, WireProtocol}
import io.iohk.scevm.network.p2p.{Message, ProtocolVersions}
import io.iohk.scevm.network.{PeerInfo, RemoteStatus}
import io.iohk.scevm.utils.Logger

import scala.concurrent.duration.FiniteDuration

sealed trait HandshakerState[F[_]] {

  /** Processes a received message and obtains a new Handshaker if the handshaker handles the received message
    *
    * @param receivedMessage, message received and to be processed
    * @return handshaker after the message was processed or None if it doesn't change
    */
  def applyMessage(receivedMessage: Message, isBootstrapPeer: Boolean = false): Option[HandshakerState[F]] =
    messageHandler(isBootstrapPeer).lift(receivedMessage)

  /** Function that is only defined at the messages handled by the current state, returns the new state after processing them.
    * If defined, it processes a message and obtains a new state of the handshake
    */
  def messageHandler(isBootstrapPeer: Boolean): PartialFunction[Message, HandshakerState[F]]

  /** Processes a timeout to the latest message sent and obtains the new Handshaker
    *
    * @return handshaker after the timeout was processed
    */
  def processTimeout: HandshakerState[F] =
    HandshakeFailure(WireProtocol.Disconnect.Reasons.TimeoutOnReceivingAMessage)
}

sealed trait HandshakeInProgress[F[_]] extends HandshakerState[F] {

  /** Obtains the next message to be sent
    *
    * @return next message to be sent
    */
  def nextMessage: NextMessage[F]
}

object HandshakeInProgress {
  def unapply[F[_]](handshakeInProgress: HandshakeInProgress[F]): Some[NextMessage[F]] =
    Some(handshakeInProgress.nextMessage)
}

final case class HandshakeFailure[F[_]](reason: Int) extends HandshakerState[F] {
  def messageHandler(isBootstrapPeer: Boolean): PartialFunction[Message, HandshakerState[F]] =
    PartialFunction.empty

  override val processTimeout: HandshakerState[F] =
    this
}

final case class HandshakeSuccess[F[_]](result: PeerInfo) extends HandshakerState[F] {
  def messageHandler(isBootstrapPeer: Boolean): PartialFunction[Message, HandshakerState[F]] =
    PartialFunction.empty

  override val processTimeout: HandshakerState[F] =
    this
}

final case class NextMessage[F[_]](messageToSend: F[Message], timeout: FiniteDuration)

sealed trait NodeStatusExchangeState[F[_]] extends HandshakeInProgress[F] with Logger {

  val handshakerConfiguration: ObftHandshakerConfig[F]

  import handshakerConfiguration._

  lazy val nextMessage: NextMessage[F] =
    NextMessage(
      messageToSend = createStatusMsg(),
      timeout = peerConfiguration.waitForStatusTimeout
    )

  override def processTimeout: HandshakerState[F] = {
    log.debug("Timeout while waiting status")
    super.processTimeout
  }

  protected def applyRemoteStatusMessage(isBootstrapPeer: Boolean, status: RemoteStatus): HandshakerState[F] = {
    log.debug("Peer returned status ({})", status)

    def check[T: Show](name: String, localValue: T, peerValue: T): ValidatedNel[String, Unit] =
      Validated.condNel(localValue == peerValue, (), show"$name(local: $localValue, peer's: $peerValue)")

    val handshakeCheckResult = List(
      check("networkID", blockchainConfig.networkId, status.networkId),
      check("chainId", blockchainConfig.chainId, status.chainId),
      check("genesisHash", genesisHash, status.genesisHash),
      check("stabilityParameter", blockchainConfig.stabilityParameter, status.stabilityParameter),
      check("modeConfigHash", modeConfigHash, status.modeConfigHash),
      check("slotDuration", blockchainConfig.slotDuration, status.slotDuration)
    ).sequence

    handshakeCheckResult match {
      case Validated.Valid(_) => HandshakeSuccess(PeerInfo.empty)
      case Validated.Invalid(mismatches) =>
        lazy val mismatchesStr = mismatches.toList.mkString(", ")
        if (isBootstrapPeer)
          log.error(s"Handshake with bootstrap peer failed because of mismatches in: $mismatchesStr")
        else log.debug(s"Handshake with peer failed because of mismatches in: $mismatchesStr")
        HandshakeFailure(Reasons.DisconnectRequested)
    }
  }

  protected def createStatusMsg(): F[Message]
}

final private[network] case class ObftNodeStatusExchangeState(handshakerConfiguration: ObftHandshakerConfig[IO])
    extends NodeStatusExchangeState[IO] {

  import handshakerConfiguration._

  override protected[handshaker] def createStatusMsg(): IO[Message] = for {
    CurrentBranch(stable, best) <- CurrentBranch.get
    status = OBTF1Messages.Status(
               protocolVersion = ProtocolVersions.PV1,
               networkId = blockchainConfig.networkId,
               chainId = blockchainConfig.chainId,
               stableHash = stable.hash,
               genesisHash = genesisHash,
               forkId = ForkId.create(genesisHash, blockchainConfig)(best.number),
               stabilityParameter = blockchainConfig.stabilityParameter,
               slotDuration = blockchainConfig.slotDuration,
               modeConfigHash = modeConfigHash
             )
    _ = log.debug(s"sending status $status")
  } yield status

  def messageHandler(isBootstrapPeer: Boolean): PartialFunction[Message, HandshakerState[IO]] = {
    case status: OBTF1Messages.Status =>
      import cats.effect.unsafe.implicits.global
      (for {
        stableBlockHeader <- CurrentBranch.stable[IO]
        validationResult <-
          ForkIdValidator.validatePeer[IO](handshakerConfiguration.genesisHash, blockchainConfig)(
            stableBlockHeader.number,
            status.forkId
          )
      } yield validationResult match {
        case Connect => applyRemoteStatusMessage(isBootstrapPeer, RemoteStatus(status))
        case _       => HandshakeFailure[IO](Reasons.UselessPeer)
      }).unsafeRunSync()
  }

}

final private[network] case class HelloExchangeState(handshakerConfiguration: ObftHandshakerConfig[IO])
    extends HandshakeInProgress[IO]
    with Logger {

  import handshakerConfiguration._

  override lazy val nextMessage: NextMessage[IO] = {
    log.debug("RLPx connection established, sending Hello")
    NextMessage[IO](
      messageToSend =
        nodeStatusHolder.getNodeStatus.map(Hello.fromNodeStatus(_, handshakerConfiguration.appConfig.clientId)),
      timeout = peerConfiguration.waitForHelloTimeout
    )
  }

  def messageHandler(isBootstrapPeer: Boolean): PartialFunction[Message, HandshakerState[IO]] = { case hello: Hello =>
    log.debug("Protocol handshake finished with peer ({})", hello)
    if (hello.capabilities.contains(OBFT1))
      ObftNodeStatusExchangeState(handshakerConfiguration)
    else {
      log.debug(show"Connected peer [${hello.nodeId}] does not support OBFT protocol. Disconnecting.")
      HandshakeFailure(Disconnect.Reasons.IncompatibleP2pProtocolVersion)
    }

  }

  override def processTimeout: HandshakerState[IO] = {
    log.debug("Timeout while waiting for Hello")
    super.processTimeout
  }

}
