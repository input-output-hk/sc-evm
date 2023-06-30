//package io.iohk.scevm.network

// TODO needs updating because of EphemBlockchainTestSetup
// import akka.actor.{ActorSystem, Props}
// import akka.testkit.{TestActorRef, TestProbe}
// import io.iohk.bytes.ByteString
// import com.miguno.akka.testing.VirtualTime
// import io.iohk.scevm.Mocks.{MockHandshakerAlwaysFails, MockHandshakerAlwaysSucceeds}
// import io.iohk.scevm.blockchain.sync.EphemBlockchainTestSetup
// import io.iohk.scevm.network.ObftPeerManagerActor.{PeerInfo, RemoteStatus}
// import io.iohk.scevm.network.PeerActor.Status.Handshaked
// import io.iohk.scevm.network.PeerActor.{ConnectTo, GetStatus, StatusResponse}
// import io.iohk.scevm.network.handshaker.Handshaker.NextMessage
// import io.iohk.scevm.network.handshaker._
// import io.iohk.scevm.network.p2p.Message
// import io.iohk.scevm.network.p2p.messages.Capability.Capabilities._
// import io.iohk.scevm.network.p2p.messages.PV100.Status
// import io.iohk.scevm.network.p2p.messages.ProtocolVersions
// import io.iohk.scevm.network.p2p.messages.WireProtocol.{Disconnect, Hello, Pong}
// import io.iohk.scevm.network.rlpx.RLPxConnectionHandler
// import io.iohk.scevm.config.Config
// import io.iohk.scevm.{Fixtures, Timeouts}
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatest.matchers.should.Matchers

// import java.net.{InetSocketAddress, URI}

// class PeerActorHandshakingSpec extends AnyFlatSpec with Matchers {

//   it should "succeed in establishing connection if the handshake is always successful" in new TestSetup {

//     import DefaultValues._

//     val peerActorHandshakeSucceeds =
//       peerActor(MockHandshakerAlwaysSucceeds(defaultStatus, defaultBlockNumber))

//     //Establish probe rlpxconnection
//     peerActorHandshakeSucceeds ! ConnectTo(uri)
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
//     rlpxConnectionProbe.reply(RLPxConnectionHandler.ConnectionEstablished(ByteString()))

//     //Test that the handshake succeeded
//     val sender = TestProbe()(system)
//     sender.send(peerActorHandshakeSucceeds, GetStatus)
//     sender.expectMsg(StatusResponse(Handshaked))
//   }

//   it should "fail in establishing connection if the handshake always fails" in new TestSetup {

//     import DefaultValues._

//     val peerActorHandshakeFails = peerActor(MockHandshakerAlwaysFails(defaultReasonDisconnect))

//     //Establish probe rlpxconnection
//     peerActorHandshakeFails ! ConnectTo(uri)
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
//     rlpxConnectionProbe.reply(RLPxConnectionHandler.ConnectionEstablished(ByteString()))

//     //Test that the handshake failed
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(defaultReasonDisconnect)))

//   }

//   it should "succeed in establishing connection in simple Hello exchange" in new TestSetup {

//     import DefaultValues._

//     val peerActorHandshakeRequiresHello = peerActor(MockHandshakerRequiresHello())

//     //Establish probe rlpxconnection
//     peerActorHandshakeRequiresHello ! ConnectTo(uri)
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
//     rlpxConnectionProbe.reply(RLPxConnectionHandler.ConnectionEstablished(ByteString()))

//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
//     peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(defaultHello)

//     //Test that the handshake succeeded
//     val sender = TestProbe()(system)
//     sender.send(peerActorHandshakeRequiresHello, GetStatus)
//     sender.expectMsg(StatusResponse(Handshaked))
//   }

//   it should "fail in establishing connection in simple Hello exchange if timeout happened" in new TestSetup {

//     import DefaultValues._

//     val peerActorHandshakeRequiresHello = peerActor(MockHandshakerRequiresHello())

//     //Establish probe rlpxconnection
//     peerActorHandshakeRequiresHello ! ConnectTo(uri)
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
//     rlpxConnectionProbe.reply(RLPxConnectionHandler.ConnectionEstablished(ByteString()))

//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
//     time.advance(defaultTimeout * 2)

//     //Test that the handshake failed
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(defaultReasonDisconnect)))
//   }

//   it should "fail in establishing connection in simple Hello exchange if a Status message was received" in new TestSetup {

//     import DefaultValues._

//     val peerActorHandshakeRequiresHello = peerActor(MockHandshakerRequiresHello())

//     //Establish probe rlpxconnection
//     peerActorHandshakeRequiresHello ! ConnectTo(uri)
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
//     rlpxConnectionProbe.reply(RLPxConnectionHandler.ConnectionEstablished(ByteString()))

//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
//     peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(defaultStatusMsg)

//     //Test that the handshake failed
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(defaultReasonDisconnect)))
//   }

//   it should "ignore unhandled message while establishing connection" in new TestSetup {

//     import DefaultValues._

//     val peerActorHandshakeRequiresHello = peerActor(MockHandshakerRequiresHello())

//     //Establish probe rlpxconnection
//     peerActorHandshakeRequiresHello ! ConnectTo(uri)
//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
//     rlpxConnectionProbe.reply(RLPxConnectionHandler.ConnectionEstablished(ByteString()))

//     rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
//     peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(Pong()) //Ignored
//     peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(Pong()) //Ignored
//     peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(Pong()) //Ignored
//     peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(defaultHello)

//     //Test that the handshake succeeded
//     val sender = TestProbe()(system)
//     sender.send(peerActorHandshakeRequiresHello, GetStatus)
//     sender.expectMsg(StatusResponse(Handshaked))
//   }

//   trait TestSetup extends EphemBlockchainTestSetup {
//     implicit override lazy val system: ActorSystem = ActorSystem("PeerActorSpec_System")

//     val time = new VirtualTime

//     val uri = new URI(
//       "enode://18a551bee469c2e02de660ab01dede06503c986f6b8520cb5a65ad122df88b17b285e3fef09a40a0d44f99e014f8616cf1ebc2e094f96c6e09e2f390f5d34857@47.90.36.129:30303"
//     )
//     val rlpxConnectionProbe: TestProbe = TestProbe()
//     val peerMessageBus: TestProbe = TestProbe()
//     val knownNodesManager: TestProbe = TestProbe()

//     def peerActor(handshaker: Handshaker[PeerInfo]): TestActorRef[PeerActor[PeerInfo]] = TestActorRef(
//       Props(
//         new PeerActor(
//           new InetSocketAddress("127.0.0.1", 0),
//           rlpxConnectionFactory = _ => rlpxConnectionProbe.ref,
//           peerConfiguration = Config.Network.peer,
//           peerEventBus = peerMessageBus.ref,
//           knownNodesManager = knownNodesManager.ref,
//           incomingConnection = false,
//           externalSchedulerOpt = Some(time.scheduler),
//           initHandshaker = handshaker
//         )
//       )
//     )
//   }

//   object DefaultValues {
//     val defaultStatusMsg: Status = Status(
//       protocolVersion = ProtocolVersions.PV100,
//       networkId = 1,
//       bestHash = Fixtures.Blocks.Genesis.header.hash,
//       genesisHash = Fixtures.Blocks.Genesis.header.hash
//     )
//     val defaultStatus: RemoteStatus = RemoteStatus(defaultStatusMsg)
//     val defaultBlockNumber = 1000
//     val defaultForkAccepted = true

//     val defaultPeerInfo: PeerInfo = PeerInfo(
//       defaultStatus,
//       defaultBlockNumber,
//       defaultStatus.bestHash
//     )

//     val defaultReasonDisconnect = Disconnect.Reasons.Other

//     val defaultHello: Hello = Hello(
//       p2pVersion = 0,
//       clientId = "notused",
//       capabilities = Seq(OBFTCapability),
//       listenPort = 0,
//       nodeId = ByteString.empty
//     )
//     val defaultTimeout = Timeouts.normalTimeout
//   }

//   case class MockHandshakerRequiresHello private (handshakerState: HandshakerState[PeerInfo])
//       extends Handshaker[PeerInfo] {
//     override def copy(newState: HandshakerState[PeerInfo]): Handshaker[PeerInfo] = new MockHandshakerRequiresHello(
//       newState
//     )
//   }

//   object MockHandshakerRequiresHello {
//     def apply(): MockHandshakerRequiresHello =
//       new MockHandshakerRequiresHello(MockHelloExchangeState)
//   }

//   case object MockHelloExchangeState extends InProgressState[PeerInfo] {

//     import DefaultValues._

//     def nextMessage: NextMessage = NextMessage(defaultHello, defaultTimeout)

//     def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
//       case helloMsg: Hello => ConnectedState(defaultPeerInfo)
//       case status: Status  => DisconnectedState(defaultReasonDisconnect)
//     }

//     def processTimeout: HandshakerState[PeerInfo] = DisconnectedState(defaultReasonDisconnect)
//   }

// }
