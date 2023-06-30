package io.iohk.scevm.network.p2p.messages

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp
import io.iohk.ethereum.rlp._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockHash, BlockNumber, NodeId}
import io.iohk.scevm.network.RequestId
import io.iohk.scevm.network.forkid.ForkId
import io.iohk.scevm.network.p2p.Capability.Capabilities
import io.iohk.scevm.network.p2p.messages.OBFT1._
import io.iohk.scevm.network.p2p.messages.WireProtocol._
import io.iohk.scevm.network.p2p.{Codes, MessageDecoder, NetworkMessageDecoder, ObftMessageDecoder}
import io.iohk.scevm.testing.BlockGenerators.obftChainBlockWithoutTransactionsGen
import io.iohk.scevm.testing.{BlockGenerators, TestCoreConfigs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration.DurationInt

class MessagesSerializationSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  import io.iohk.scevm.testing.fixtures._

  "Wire Protocol" when {

    "encoding and decoding Hello" should {
      "return same result" in {
        verify(
          Hello(1, "teest", Seq(Capabilities.OBFT1), 1, NodeId(ByteString("Id"))),
          (m: Hello) => m.toBytes,
          Hello.code
        )
      }
    }

    "encoding and decoding Disconnect" should {
      "return same result" in {
        verify(
          Disconnect(Disconnect.Reasons.AlreadyConnected),
          (m: Disconnect) => m.toBytes,
          Disconnect.code
        )
      }
    }

    "encoding and decoding Ping" should {
      "return same result" in {
        verify(Ping(), (m: Ping) => m.toBytes, Ping.code)
      }
    }

    "encoding and decoding Pong" should {
      "return same result" in {
        verify(Pong(), (m: Pong) => m.toBytes, Pong.code)
      }
    }
  }

  "encoding and decoding Status" should {
    "return same result for Status v63" in {
      val genesisHash = BlockHash(ByteString("HASH"))
      val msg = Status(
        1,
        2,
        ChainId(3),
        genesisHash,
        BlockHash(ByteString("HASH2")),
        ForkId.create(genesisHash, TestCoreConfigs.blockchainConfig)(BlockNumber(1)),
        15,
        5.seconds,
        ByteString(1)
      )
      verify(msg, (m: Status) => m.toBytes, Codes.StatusCode)
    }
  }

  "encoding and decoding SignedTransactions" should {
    "return same result" in {
      val msg = SignedTransactions(ValidBlock.body.transactionList)
      verify(msg, (m: SignedTransactions) => m.toBytes, Codes.SignedTransactionsCode)
    }
  }

  "encoding and decoding NewBlock" should {
    "return same result for NewBlock" in {
      val msg = NewBlock(ValidBlock.block)
      verify(msg, (m: NewBlock) => m.toBytes, Codes.NewBlockCode)
    }
  }
  "encoding and decoding NewBlockHashes" should {
    "return same result" in {
      val msg = NewBlockHashes(
        Seq(
          BlockHashAndNumber(BlockHash(ByteString("23")), 1),
          BlockHashAndNumber(BlockHash(ByteString("10")), 2),
          BlockHashAndNumber(BlockHash(ByteString("36")), 3)
        )
      )
      verify(msg, (m: NewBlockHashes) => m.toBytes, Codes.NewBlockHashesCode)
    }
  }

  "encoding and decoding BlockBodies" should {
    "return same result" in {
      val msg = BlockBodies(RequestId(1), Seq(ValidBlock.body, ValidBlock.body))
      verify(msg, (m: BlockBodies) => m.toBytes, Codes.BlockBodiesCode)
    }
  }

  "encoding and decoding GetBlockBodies" should {
    "return same result" in {
      val msg = GetBlockBodies(Seq(BlockHash(ByteString("111")), BlockHash(ByteString("2222"))))
      verify(msg, (m: GetBlockBodies) => m.toBytes, Codes.GetBlockBodiesCode)
    }
  }

  "encoding and decoding BlockHeaders" should {
    "return same result" in {
      val msg = BlockHeaders(RequestId(3), BlockGenerators.obftChainHeaderGen(2, 20).sample.get)
      verify(msg, (m: BlockHeaders) => m.toBytes, Codes.BlockHeadersCode)
    }
  }

  "encoding and decoding GetBlockHeaders" should {
    "return same result" in {
      verify(
        GetBlockHeaders(Left(BlockNumber(1)), 1, 1, reverse = false),
        (m: GetBlockHeaders) => m.toBytes,
        Codes.GetBlockHeadersCode
      )
      verify(
        GetBlockHeaders(Right(BlockHash(ByteString("1" * 32))), 1, 1, reverse = true),
        (m: GetBlockHeaders) => m.toBytes,
        Codes.GetBlockHeadersCode
      )
    }
  }

  "encoding and decoding GetStableHeaders" should {
    "return same result" in {
      verify(
        GetStableHeaders(Seq(1, 2, 3)),
        (m: GetStableHeaders) => m.toBytes,
        Codes.GetStableHeadersCode
      )
    }
    "not evolve" in {
      val message = GetStableHeaders(Seq(1, 2, 3))
      val encoded = GetStableHeaders.rlpCodec.encode(message)
      val expectedEncoded =
        RLPList(
          RLPValue(Hex.decodeAsArrayUnsafe("1c3957741c395774")),
          RLPList(RLPValue(Array(1.toByte)), RLPValue(Array(2.toByte)), RLPValue(Array(3.toByte)))
        )

      rlp.encode(encoded) shouldBe rlp.encode(expectedEncoded)
    }
  }

  "encoding and decoding StableHeaders" should {
    "return same result" in {
      verify(
        StableHeaders(RequestId(1L), ValidBlock.header, Seq.empty),
        (m: StableHeaders) => m.toBytes,
        Codes.StableHeadersCode
      )
      verify(
        StableHeaders(RequestId(1L), ValidBlock.header, Seq(ValidBlock.header)),
        (m: StableHeaders) => m.toBytes,
        Codes.StableHeadersCode
      )
    }
    "not evolve" in {
      val message = StableHeaders(RequestId(1L), ValidBlock.header, Seq.empty)
      val encoded = StableHeaders.rlpCodec.encode(message)

      val expectedEncoded =
        RLPList(
          RLPValue(Array(1.toByte)),
          RLPList(
            RLPValue(Hex.decodeAsArrayUnsafe("8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71")),
            RLPValue(Hex.decodeAsArrayUnsafe("2fb079")),
            RLPValue(Hex.decodeAsArrayUnsafe("11d7")),
            RLPValue(Hex.decodeAsArrayUnsafe("df7d7e053933b5cc24372f878c90e62dadad5d42")),
            RLPValue(Hex.decodeAsArrayUnsafe("087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67")),
            RLPValue(Hex.decodeAsArrayUnsafe("8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac")),
            RLPValue(Hex.decodeAsArrayUnsafe("8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d")),
            RLPValue(
              Hex.decodeAsArrayUnsafe(
                "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
              )
            ),
            RLPValue(Hex.decodeAsArrayUnsafe("47b75c")),
            RLPValue(Hex.decodeAsArrayUnsafe("014820")),
            RLPValue(Hex.decodeAsArrayUnsafe("58948fdd")),
            RLPValue(
              Hex.decodeAsArrayUnsafe(
                "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5b4"
              )
            ),
            RLPValue(
              Hex.decodeAsArrayUnsafe(
                "f3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d9540153629a403579f5ce57bcbefba2616b1c6156d308ddcd37372c94943fdabeda971c"
              )
            )
          ),
          RLPList()
        )

      rlp.encode(encoded) shouldBe rlp.encode(expectedEncoded)
    }
  }

  "encoding and decoding GetFullBlock" should {
    "return same result" in {
      val msg = GetFullBlocks(BlockHash(ByteString("42")), 4)
      verify(msg, (m: GetFullBlocks) => m.toBytes, Codes.GetFullBlocksCode)
    }
  }

  "encoding and decoding FullBlock" should {
    "return same result" in {
      forAll(obftChainBlockWithoutTransactionsGen(0, 10)) { blocks =>
        val msg = FullBlocks(RequestId(40), blocks)
        verify(msg, (m: FullBlocks) => m.toBytes, Codes.FullBlocksCode)
      }
    }
  }

  val messageDecoder: MessageDecoder = NetworkMessageDecoder.orElse(ObftMessageDecoder)

  def verify[T](msg: T, encode: T => Array[Byte], code: Int): Unit =
    messageDecoder.fromBytes(code, encode(msg)) shouldEqual Right(msg)

}
