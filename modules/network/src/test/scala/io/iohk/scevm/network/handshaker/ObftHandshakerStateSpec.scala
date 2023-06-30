package io.iohk.scevm.network.handshaker

import cats.effect.IO
import com.softwaremill.diffx.generic.auto.diffForCaseClass
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.generateKeyPair
import io.iohk.ethereum.rlp.decode
import io.iohk.ethereum.utils.ByteStringUtils._
import io.iohk.scevm.config.{AppConfig, BlockchainConfig}
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock}
import io.iohk.scevm.network.forkid.ForkId
import io.iohk.scevm.network.p2p.messages.{OBFT1, WireProtocol}
import io.iohk.scevm.network.p2p.{Capability, P2pVersion, ProtocolVersions}
import io.iohk.scevm.network.{PeerConfig, PeerInfo, RemoteStatus, TestNetworkConfigs}
import io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen
import io.iohk.scevm.testing.CryptoGenerators.asymmetricCipherKeyPairGen
import io.iohk.scevm.testing.TestCoreConfigs
import io.iohk.scevm.testing.fixtures._
import io.iohk.scevm.utils.{NodeState, NodeStatus, NodeStatusProvider, ServerStatus}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.security.SecureRandom

class ObftHandshakerStateSpec
    extends AnyFlatSpec
    with Matchers
    with MockFactory
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with DiffShouldMatcher { suite =>

  import cats.effect.unsafe.implicits.global
  private val networkConfig    = TestNetworkConfigs.networkConfig
  private val blockchainConfig = TestCoreConfigs.blockchainConfig
  private val baseConfig       = TestCoreConfigs.appConfig

  it should "send status with fork id when peer supports OBFT/1" in new LocalPeerObftSetup with RemotePeerObftSetup {
    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg))
      .shouldMatchTo(
        // initial max block number stays at 0 until block broadcast is received
        Some(HandshakeSuccess(PeerInfo(None)))
      )
  }

  it should "disconnect from a useless peer because of validating fork id when peer supports OBFT/1" in new LocalPeerObftSetup
    with RemotePeerObftSetup {

    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg.copy(forkId = ForkId(1, None))))
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.UselessPeer))
      )
  }

  it should "fail if a timeout happened during hello exchange" in new TestSetup {
    ObftHandshaker(handshakerConfig).processTimeout.shouldMatchTo(
      HandshakeFailure(WireProtocol.Disconnect.Reasons.TimeoutOnReceivingAMessage)
    )
  }

  it should "fail if a timeout happened during status exchange" in new RemotePeerObftSetup {
    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .map(_.processTimeout)
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.TimeoutOnReceivingAMessage))
      )
  }

  it should "fail if a status msg is received with invalid network id" in new LocalPeerObftSetup
    with RemotePeerObftSetup {

    val wrongNetworkId: Int = localStatus.networkId + 1

    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg.copy(networkId = wrongNetworkId)))
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.DisconnectRequested))
      )
  }

  it should "fail if a status msg is received with invalid genesisHash" in new LocalPeerObftSetup
    with RemotePeerObftSetup {
    val wrongGenesisHash: BlockHash =
      BlockHash(
        concatByteStrings((localStatus.genesisHash.byteString.head + 1).toByte, localStatus.genesisHash.byteString.tail)
      )

    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg.copy(genesisHash = wrongGenesisHash)))
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.DisconnectRequested))
      )
  }

  it should "succeed if a status msg is received with valid genesisHash" in new LocalPeerObftSetup
    with RemotePeerObftSetup {

    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg))
      .shouldMatchTo(
        Some(HandshakeSuccess(PeerInfo(None)))
      )
  }

  it should "fail if the remote peer doesn't support capabilities" in new RemotePeerObftSetup {
    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello.copy(capabilities = Nil))
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.IncompatibleP2pProtocolVersion))
      )
  }

  it should "fail if the remote peer has a different stability parameter" in new RemotePeerObftSetup {
    val wrongStabilityParam: Int = blockchainConfig.stabilityParameter + 1

    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg.copy(stabilityParameter = wrongStabilityParam)))
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.DisconnectRequested))
      )
  }

  it should "fail if the remote peer has a different mode config hash" in new RemotePeerObftSetup {
    val wrongModeConfigHash: ByteString = ByteString("bad")

    ObftHandshaker(handshakerConfig)
      .applyMessage(remoteHello)
      .flatMap(_.applyMessage(remoteStatusMsg.copy(modeConfigHash = wrongModeConfigHash)))
      .shouldMatchTo(
        Some(HandshakeFailure(WireProtocol.Disconnect.Reasons.DisconnectRequested))
      )
  }

  it should "create PeerStatus message with stableBlockHash" in new LocalPeerObftSetup {
    forAll(obftBlockHeaderGen, obftBlockHeaderGen) { (stableHeader, bestHeader) =>
      implicit val current: CurrentBranch.Signal[IO] = Signal.constant(CurrentBranch(stableHeader, bestHeader))

      val expectedForkId =
        ForkId.create(genesisBlock.header.hash, blockchainConfig)(bestHeader.number)
      (for {
        res <- ObftNodeStatusExchangeState(handshakerConfig).createStatusMsg()
      } yield decode[OBFT1.Status](res.toBytes) shouldBe localStatusMsg.copy(
        stableHash = stableHeader.hash,
        forkId = expectedForkId
      )).unsafeRunSync()
    }
  }

  trait TestSetup {
    val genesisBlock: ObftBlock = ValidBlock.block
    val secureRandom            = new SecureRandom()

    val nodeStatus: NodeStatus = NodeStatus(
      key = generateKeyPair(secureRandom),
      serverStatus = ServerStatus.NotListening,
      nodeState = NodeState.Running
    )

    implicit val current: CurrentBranch.Signal[IO] =
      Signal.constant(CurrentBranch(genesisBlock.header, genesisBlock.header))

    val configHash: ByteString = ByteString("abcdef")

    def handshakerConfig(implicit currentSignal: CurrentBranch.Signal[IO]): ObftHandshakerConfig[IO] =
      new ObftHandshakerConfig[IO] {
        override def genesisHash: BlockHash = genesisBlock.hash
        override def nodeStatusHolder: NodeStatusProvider[IO] =
          NodeStatusProvider[IO](asymmetricCipherKeyPairGen.sample.get).unsafeRunSync()
        override def peerConfiguration: PeerConfig                    = networkConfig.peer
        override def blockchainConfig: BlockchainConfig               = suite.blockchainConfig
        override def appConfig: AppConfig                             = suite.baseConfig
        implicit override def currentBranch: CurrentBranch.Signal[IO] = currentSignal
        override def modeConfigHash: ByteString                       = configHash
      }

    val firstBlock: ObftBlock =
      genesisBlock.copy(header =
        genesisBlock.header.copy(parentHash = genesisBlock.header.hash, number = BlockNumber(1))
      )
  }

  trait LocalPeerSetup extends TestSetup {
    val localHello: WireProtocol.Hello = WireProtocol.Hello(
      p2pVersion = P2pVersion,
      clientId = baseConfig.clientId,
      capabilities = Seq(Capability.Capabilities.OBFT1),
      listenPort = 0, //Local node not listening
      nodeId = nodeStatus.nodeId
    )
  }

  trait LocalPeerObftSetup extends LocalPeerSetup {
    val localStatusMsg: OBFT1.Status = OBFT1.Status(
      protocolVersion = ProtocolVersions.PV1,
      networkId = blockchainConfig.networkId,
      chainId = blockchainConfig.chainId,
      stableHash = genesisBlock.header.hash,
      genesisHash = genesisBlock.header.hash,
      forkId = ForkId.create(genesisBlock.header.hash, blockchainConfig)(genesisBlock.header.number),
      stabilityParameter = blockchainConfig.stabilityParameter,
      slotDuration = blockchainConfig.slotDuration,
      modeConfigHash = configHash
    )
    val localStatus: RemoteStatus = RemoteStatus(localStatusMsg)
  }

  trait RemotePeerSetup extends TestSetup {
    val remoteNodeStatus: NodeStatus = NodeStatus(
      key = generateKeyPair(secureRandom),
      serverStatus = ServerStatus.NotListening,
      nodeState = NodeState.Running
    )
    val remotePort = 8545
  }

  trait RemotePeerObftSetup extends RemotePeerSetup {
    val remoteHello: WireProtocol.Hello = WireProtocol.Hello(
      p2pVersion = P2pVersion,
      clientId = "remote-peer",
      capabilities = Seq(Capability.Capabilities.OBFT1),
      listenPort = remotePort,
      nodeId = remoteNodeStatus.nodeId
    )

    val remoteStatusMsg: OBFT1.Status = OBFT1.Status(
      protocolVersion = ProtocolVersions.PV1,
      networkId = blockchainConfig.networkId,
      chainId = blockchainConfig.chainId,
      stableHash = genesisBlock.header.hash,
      genesisHash = genesisBlock.header.hash,
      forkId = ForkId.create(genesisBlock.header.hash, blockchainConfig)(genesisBlock.header.number),
      stabilityParameter = blockchainConfig.stabilityParameter,
      slotDuration = blockchainConfig.slotDuration,
      modeConfigHash = configHash
    )

    val remoteStatus: RemoteStatus = RemoteStatus(remoteStatusMsg)
  }
}
