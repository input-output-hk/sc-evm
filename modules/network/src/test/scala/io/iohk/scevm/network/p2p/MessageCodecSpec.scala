package io.iohk.scevm.network.p2p

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{BlockHash, BlockNumber, NodeId}
import io.iohk.scevm.network.forkid.ForkId
import io.iohk.scevm.network.p2p
import io.iohk.scevm.network.p2p.Capability.Capabilities._
import io.iohk.scevm.network.p2p.messages.OBFT1.Status
import io.iohk.scevm.network.p2p.messages.WireProtocol.Hello
import io.iohk.scevm.network.rlpx.{FrameCodec, MessageCodec}
import io.iohk.scevm.testing.TestCoreConfigs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MessageCodecSpec extends AnyFlatSpec with Matchers {
  private val blockchainConfig = TestCoreConfigs.blockchainConfig
  private val baseConfig       = TestCoreConfigs.appConfig

  it should "not compress messages when remote side advertises p2p version less than 5" in new TestSetup {
    val remoteHello = remoteMessageCodec.encodeMessage(helloV4)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello    = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus = remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // remote peer did not receive local status so it treats all remote messages as uncompressed
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head == Right(status))
  }

  it should "compress messages when remote side advertises p2p version larger or equal 5" in new TestSetup {
    val remoteHello = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello    = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus = remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // remote peer did not receive local status so it treats all remote messages as uncompressed,
    // but local peer compress messages after V5 Hello message
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head.isLeft)
  }

  it should "compress messages when both sides advertises p2p version larger or equal 5" in new TestSetup {
    val remoteHello = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    val localNextMessageAfterHello      = messageCodec.encodeMessage(status)
    val remoteReadNextMessageAfterHello = remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // both peers exchanged v5 hellos, so they should send compressed messages
    assert(remoteReadNextMessageAfterHello.size == 1)
    assert(remoteReadNextMessageAfterHello.head == Right(status))
  }

  it should "compress and decompress first message after hello when receiving 2 frames" in new TestSetup {
    val remoteHello = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    // hello won't be compressed as per spec it never is, and status will be compressed as remote peer advertised proper versions
    val localHello  = messageCodec.encodeMessage(helloV5)
    val localStatus = messageCodec.encodeMessage(status)

    // both messages will be read at one, but after reading hello decompressing will be activated
    val remoteReadBothMessages = remoteMessageCodec.readMessages(localHello ++ localStatus)

    // both peers exchanged v5 hellos, so they should send compressed messages
    assert(remoteReadBothMessages.size == 2)
    assert(remoteReadBothMessages.head == Right(helloV5))
    assert(remoteReadBothMessages.last == Right(status))
  }

  trait TestSetup extends SecureChannelSetup {
    val frameCodec       = new FrameCodec(secrets)
    val remoteFrameCodec = new FrameCodec(remoteSecrets)

    val helloV5: Hello = Hello(
      p2pVersion = p2p.P2pVersion,
      clientId = baseConfig.clientId,
      capabilities = Seq(OBFT1),
      listenPort = 0, //Local node not listening
      nodeId = NodeId(ByteString(1))
    )

    val helloV4: Hello = helloV5.copy(p2pVersion = 4)

    val fakeHash: BlockHash        = BlockHash(ByteString(1))
    val modeConfigHash: ByteString = ByteString("abdc")
    val status: Status = Status(
      protocolVersion = ProtocolVersions.PV1,
      networkId = blockchainConfig.networkId,
      chainId = blockchainConfig.chainId,
      stableHash = fakeHash,
      genesisHash = fakeHash,
      forkId = ForkId.create(fakeHash, blockchainConfig)(BlockNumber(1)),
      stabilityParameter = blockchainConfig.stabilityParameter,
      slotDuration = blockchainConfig.slotDuration,
      modeConfigHash = modeConfigHash
    )

    val decoder: MessageDecoder = NetworkMessageDecoder.orElse(ObftMessageDecoder)

    val messageCodec       = new MessageCodec(frameCodec, decoder)
    val remoteMessageCodec = new MessageCodec(remoteFrameCodec, decoder)

  }

}
