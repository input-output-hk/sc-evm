package io.iohk.scevm.network.p2p

import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockHash, BlockNumber, NodeId}
import io.iohk.scevm.network.RequestId
import io.iohk.scevm.network.forkid.ForkId
import io.iohk.scevm.network.p2p.Capability.Capabilities
import io.iohk.scevm.network.p2p.messages.OBFT1._
import io.iohk.scevm.network.p2p.messages._
import io.iohk.scevm.testing.{BlockGenerators, Generators, TransactionGenerators}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.DurationInt

class MessageDecodersSpec extends AnyWordSpec with Matchers {

  def decode: ProtocolVersions.Version => MessageDecoder = EthereumMessageDecoder.ethMessageDecoder

  val exampleHash: ByteString = ByteString(
    Hex.decode("fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6")
  )

  "MessageDecoders" should {
    "decode wire protocol message for all versions of protocol" in {
      val hello = WireProtocol.Hello(
        p2pVersion = 4,
        clientId = "scevm",
        capabilities = Capabilities.All,
        listenPort = 3333,
        nodeId = NodeId(
          ByteString(
            Hex.decode(
              "a13f3f0555b5037827c743e40fce29139fcf8c3f2a8f12753872fe906a77ff70f6a7f517be995805ff39ab73af1d53dac1a6c9786eebc5935fc455ac8f41ba67"
            )
          )
        )
      )
      NetworkMessageDecoder.fromBytes(WireProtocol.Hello.code, hello.toBytes) shouldBe Right(hello)
    }

    "decode NewBlockHashes message for all supported versions of protocol" in {
      val newBlockHashes =
        NewBlockHashes(
          Seq(BlockHashAndNumber(BlockHash(exampleHash), 1), BlockHashAndNumber(BlockHash(exampleHash), 2))
        )

      decode(Capabilities.OBFT1.version).fromBytes(Codes.NewBlockHashesCode, newBlockHashes.toBytes) shouldBe Right(
        newBlockHashes
      )
    }

    "decode GetBlockHeaders message for all supported versions of protocol" in {
      val getBlockHeaders                   = GetBlockHeaders(Left(BlockNumber(1)), 1, 1, reverse = false)
      val getBlockHeadersBytes: Array[Byte] = getBlockHeaders.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.GetBlockHeadersCode, getBlockHeadersBytes) shouldBe Right(
        getBlockHeaders
      )
    }

    "decode BlockHeaders message for all supported versions of protocol" in {
      val blockHeaders                   = BlockHeaders(RequestId(3), BlockGenerators.obftChainHeaderGen(2, 20).sample.get)
      val blockHeadersBytes: Array[Byte] = blockHeaders.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.BlockHeadersCode, blockHeadersBytes) shouldBe Right(
        blockHeaders
      )
    }

    "decode GetBlockBodies message for all supported versions of protocol" in {
      val getBlockBodies                   = GetBlockBodies(Seq(BlockHash(exampleHash)))
      val getBlockBodiesBytes: Array[Byte] = getBlockBodies.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.GetBlockBodiesCode, getBlockBodiesBytes) shouldBe Right(
        getBlockBodies
      )
    }

    "decode BlockBodies message for all supported versions of protocol" in {
      val blockBodies =
        BlockBodies(RequestId(3), BlockGenerators.obftChainBlockWithTransactionsGen(2, 10).sample.get.map(_.body))
      val blockBodiesBytes: Array[Byte] = blockBodies.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.BlockBodiesCode, blockBodiesBytes) shouldBe Right(blockBodies)
    }

    "decode GetNodeData message for all supported versions of protocol" in {
      val getNodeData                   = GetNodeData(Seq(exampleHash))
      val getNodeDataBytes: Array[Byte] = getNodeData.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.GetNodeDataCode, getNodeDataBytes) shouldBe Right(getNodeData)
    }

    "decode NodeData message for all supported versions of protocol" in {
      val nodeData                   = NodeData(RequestId(3), Seq(exampleHash))
      val nodeDataBytes: Array[Byte] = nodeData.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.NodeDataCode, nodeDataBytes) shouldBe Right(nodeData)
    }

    "decode GetReceipts message for all supported versions of protocol" in {
      val getReceipts                   = GetReceipts(Seq(BlockHash(exampleHash)))
      val getReceiptsBytes: Array[Byte] = getReceipts.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.GetReceiptsCode, getReceiptsBytes) shouldBe Right(getReceipts)
    }

    "decode Receipts message for all supported versions of protocol" in {
      val receipts                   = Receipts(RequestId(3), Generators.receiptsGen(3).sample.get)
      val receiptsBytes: Array[Byte] = receipts.toBytes

      decode(Capabilities.OBFT1.version).fromBytes(Codes.ReceiptsCode, receiptsBytes) shouldBe Right(receipts)
    }

    "decode Status message for all supported versions of protocol" in {
      val status =
        Status(
          ProtocolVersions.PV1,
          1,
          ChainId(1),
          BlockHash(exampleHash),
          BlockHash(exampleHash),
          ForkId(BigInt(2), None),
          16,
          5.seconds,
          ByteString(1)
        )

      decode(Capabilities.OBFT1.version).fromBytes(Codes.StatusCode, status.toBytes) shouldBe Right(status)
    }

    "decode NewBlock message for all supported versions of protocol" in {
      val newBlock = NewBlock(BlockGenerators.obftBlockGen.sample.get)

      decode(Capabilities.OBFT1.version).fromBytes(Codes.NewBlockCode, newBlock.toBytes) shouldBe Right(newBlock)
    }

    "decode SignedTransactions message for all supported versions of protocol" in {
      val signedTransactions                   = SignedTransactions(TransactionGenerators.signedTxSeqGen().sample.get)
      val signedTransactionsBytes: Array[Byte] = signedTransactions.toBytes

      decode(Capabilities.OBFT1.version)
        .fromBytes(Codes.SignedTransactionsCode, signedTransactionsBytes) shouldBe Right(
        signedTransactions
      )
    }

    "not decode message of not supported protocol" in {
      val signedTransactions                   = SignedTransactions(TransactionGenerators.signedTxSeqGen().sample.get)
      val signedTransactionsBytes: Array[Byte] = signedTransactions.toBytes
      assertThrows[RuntimeException] {
        decode(Capabilities.OBFT1.version - 1).fromBytes(Codes.SignedTransactionsCode, signedTransactionsBytes)
      }
    }
  }
}
