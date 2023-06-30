package io.iohk.scevm.network.p2p

import io.iohk.ethereum.rlp.decode
import io.iohk.scevm.network.p2p.MessageDecoder.{DecodingError, RLPDecodingError, UnknownMessage, UnknownNetworkMessage}
import io.iohk.scevm.network.p2p.ProtocolVersions.{PV1, Version}
import io.iohk.scevm.network.p2p.messages.OBFT1._
import io.iohk.scevm.network.p2p.messages.WireProtocol._

import scala.util.Try

object NetworkMessageDecoder extends MessageDecoder {

  override def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] = {
    import Disconnect.DisconnectSerializers._
    import Ping.PingSerializers._
    import Pong.PongSerializers._
    import Hello.HelloSerializers._

    val rawMessageDecoder: PartialFunction[Int, Try[Message]] = {
      case Disconnect.code => Try(payload.toDisconnect)
      case Ping.code       => Try(payload.toPing)
      case Pong.code       => Try(payload.toPong)
      case Hello.code      => Try(payload.toHello)
    }
    val codeNotFound: PartialFunction[Int, Either[DecodingError, Message]] = { case _ =>
      Left(UnknownNetworkMessage(msgCode))
    }

    val messageDecoder = rawMessageDecoder
      .andThen(_.toEither)
      .andThen(_.left.map(RLPDecodingError.toDecodingError))
      .orElse(codeNotFound)

    messageDecoder.apply(msgCode)
  }

}

// scalastyle:off
object ObftMessageDecoder extends MessageDecoder {

  override def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] = {
    val rawMessageDecoder: PartialFunction[Int, Try[Message]] = {
      case Codes.StatusCode                     => Try(decode[Status](payload))
      case Codes.NewBlockHashesCode             => Try(decode[NewBlockHashes](payload))
      case Codes.SignedTransactionsCode         => Try(decode[SignedTransactions](payload))
      case Codes.GetBlockHeadersCode            => Try(decode[GetBlockHeaders](payload))
      case Codes.BlockHeadersCode               => Try(decode[BlockHeaders](payload))
      case Codes.GetBlockBodiesCode             => Try(decode[GetBlockBodies](payload))
      case Codes.BlockBodiesCode                => Try(decode[BlockBodies](payload))
      case Codes.NewBlockCode                   => Try(decode[NewBlock](payload))
      case Codes.NewPooledTransactionHashesCode => Try(decode[NewPooledTransactionHashes](payload))
      case Codes.GetPooledTransactionsCode      => Try(decode[GetPooledTransactions](payload))
      case Codes.PooledTransactionsCode         => Try(decode[PooledTransactions](payload))
      case Codes.GetNodeDataCode                => Try(decode[GetNodeData](payload))
      case Codes.NodeDataCode                   => Try(decode[NodeData](payload))
      case Codes.GetReceiptsCode                => Try(decode[GetReceipts](payload))
      case Codes.ReceiptsCode                   => Try(decode[Receipts](payload))
      case Codes.GetStableHeadersCode           => Try(decode[GetStableHeaders](payload))
      case Codes.StableHeadersCode              => Try(decode[StableHeaders](payload))
      case Codes.GetFullBlocksCode              => Try(decode[GetFullBlocks](payload))
      case Codes.FullBlocksCode                 => Try(decode[FullBlocks](payload))
    }
    val codeNotFound: PartialFunction[Int, Either[DecodingError, Message]] = { case _ =>
      Left(UnknownMessage(msgCode))
    }

    val messageDecoder = rawMessageDecoder
      .andThen(_.toEither)
      .andThen(_.left.map(RLPDecodingError.toDecodingError))
      .orElse(codeNotFound)

    messageDecoder.apply(msgCode)
  }
}

object EthereumMessageDecoder {
  def ethMessageDecoder(protocolVersion: Version): MessageDecoder =
    protocolVersion match {
      case PV1 => ObftMessageDecoder.orElse(NetworkMessageDecoder)
      case _   => throw new RuntimeException(s"Unsupported Protocol Version $protocolVersion")
    }
}
