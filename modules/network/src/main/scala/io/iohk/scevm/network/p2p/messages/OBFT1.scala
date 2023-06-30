package io.iohk.scevm.network.p2p.messages

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp._
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{valueClassEnc, _}
import io.iohk.scevm.mpt.MptNode
import io.iohk.scevm.network.RequestId
import io.iohk.scevm.network.forkid.ForkId
import io.iohk.scevm.network.p2p.{Codes, Message}
import org.bouncycastle.util.encoders.Hex

import scala.concurrent.duration.{DurationInt, FiniteDuration}

// scalastyle:off number.of.types
// scalastyle:off number.of.methods
object OBFT1 {
  sealed trait RequestMessage extends Message {
    def requestId: RequestId
  }
  sealed trait ResponseMessage extends Message {
    def requestId: RequestId
  }
  sealed trait PushMessage extends Message

  sealed abstract class Encode[T: RLPEncoder] { this: T =>
    def toRLPEncodable: RLPEncodeable = implicitly[RLPEncoder[T]].encode(this)
  }

  // old common messages
  final case class SignedTransactions(txs: Seq[SignedTransaction]) extends Encode[SignedTransactions] with PushMessage {
    val code: Int                 = Codes.SignedTransactionsCode
    override def toString: String = s"SignedTransactions { txs: ${txs.map(_.hashAsHexString)} }"
  }

  object SignedTransactions {
    import SignedTransaction.SignedTransactionRLPImplicits._
    import io.iohk.scevm.domain.TypedTransaction.TransactionsRLPAggregator

    implicit val rlpCodec: RLPCodec[SignedTransactions] =
      RLPCodec.instance(
        { case SignedTransactions(txs) => RLPList(txs.map(_.toRLPEncodable): _*) },
        { case rlpList: RLPList => SignedTransactions(rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction)) }
      )
  }

  // PV65

  final case class GetStableHeaders(requestId: RequestId, ancestorsOffsets: Seq[Int])
      extends Encode[GetStableHeaders]
      with RequestMessage {
    val code: Int = Codes.GetStableHeadersCode

    override def toString: String =
      s"GetStableHeaders { requestId: $requestId, ancestorsOffsets: $ancestorsOffsets }"
  }

  object GetStableHeaders {
    def apply(ancestors: Seq[Int]): GetStableHeaders =
      GetStableHeaders(deriveRequestId(Nil), ancestors)

    implicit val rlpCodec: RLPCodec[GetStableHeaders] =
      RLPCodec.instance(
        { case GetStableHeaders(requestId, ancestors) => RLPList(requestId, ancestors) },
        { case RLPList(requestId, ancestors: RLPList) =>
          GetStableHeaders(requestId.decodeAs[RequestId]("requestId"), fromRlpList[Int](ancestors))
        }
      )
  }

  final case class StableHeaders(requestId: RequestId, stable: ObftHeader, ancestors: Seq[ObftHeader])
      extends Encode[StableHeaders]
      with ResponseMessage {
    val code: Int = Codes.StableHeadersCode

    override def toString: String =
      s"StableHeaders { stableHeader: ${stable.idTag}, ancestorHeaders: ${ancestors.map(_.idTag)}"
  }

  object StableHeaders {

    implicit val rlpCodec: RLPCodec[StableHeaders] =
      RLPCodec.instance(
        { case StableHeaders(requestId, stable, ancestors) => RLPList(requestId, stable, toRlpList(ancestors)) },
        { case RLPList(requestId, stable, ancestors: RLPList) =>
          StableHeaders(
            requestId.decodeAs[RequestId]("requestId"),
            decode[ObftHeader](stable),
            fromRlpList[ObftHeader](ancestors)
          )
        }
      )
  }

  final case class NewPooledTransactionHashes(hashes: Seq[TransactionHash])
      extends Encode[NewPooledTransactionHashes]
      with PushMessage {
    val code: Int = Codes.NewPooledTransactionHashesCode

    override def toString: String =
      s"NewPooledTransactionHashes { hashes: $hashes }"
  }

  object NewPooledTransactionHashes {
    implicit val rlpCodec: RLPCodec[NewPooledTransactionHashes] =
      RLPCodec.instance(
        { case NewPooledTransactionHashes(hashes) => toRlpList(hashes) },
        { case rlpList: RLPList =>
          NewPooledTransactionHashes(fromRlpList[TransactionHash](rlpList))
        }
      )
  }

  final case class GetPooledTransactions(requestId: RequestId, hashes: Seq[TransactionHash])
      extends Encode[GetPooledTransactions]
      with RequestMessage {
    val code: Int = Codes.GetPooledTransactionsCode

    override def toString: String =
      s"GetPooledTransactions { requestId: $requestId hashes: $hashes }"
  }

  object GetPooledTransactions {
    def apply(hashes: Seq[TransactionHash]): GetPooledTransactions =
      GetPooledTransactions(deriveRequestId(hashes), hashes)

    implicit val rlpCodec: RLPCodec[GetPooledTransactions] =
      RLPCodec.instance(
        { case GetPooledTransactions(requestId, hashes) => RLPList(requestId, toRlpList(hashes)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          GetPooledTransactions(requestId.decodeAs[RequestId]("requestId"), fromRlpList[TransactionHash](rlpList))
        }
      )
  }

  final case class PooledTransactions(requestId: RequestId, txs: Seq[SignedTransaction])
      extends Encode[PooledTransactions]
      with ResponseMessage {
    val code: Int = Codes.PooledTransactionsCode
    override def toString: String =
      s"PooledTransactions { $requestId txs: ${txs.map(_.hashAsHexString)} }"
  }

  object PooledTransactions {
    import SignedTransaction.SignedTransactionRLPImplicits._
    import TypedTransaction.TransactionsRLPAggregator

    implicit val rlpCodec: RLPCodec[PooledTransactions] =
      RLPCodec.instance(
        { case PooledTransactions(requestId, txs) => RLPList(requestId, txs.map(_.toRLPEncodable)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          PooledTransactions(
            requestId.decodeAs[RequestId]("requestId"),
            rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction)
          )
        }
      )
  }

  // old pv62 start
  final case class BlockHashAndNumber(hash: BlockHash, number: BigInt) extends Encode[BlockHashAndNumber] {
    override def toString: String =
      s"BlockHashAndNumber { hash: $hash number: $number }"
  }

  object BlockHashAndNumber {
    implicit val rlpCodec: RLPCodec[BlockHashAndNumber] =
      RLPCodec.instance(
        { case BlockHashAndNumber(hash, number) => RLPList(hash.byteString, number) },
        { case RLPList(hash, number) => BlockHashAndNumber(BlockHash(hash), number) }
      )
  }

  final case class NewBlockHashes(hashes: Seq[BlockHashAndNumber]) extends Encode[NewBlockHashes] with PushMessage {
    val code: Int                 = Codes.NewBlockHashesCode
    override def toString: String = s"NewBlockHashes { hashes: $hashes }"
  }

  object NewBlockHashes {
    implicit val rlpCodec: RLPCodec[NewBlockHashes] =
      RLPCodec.instance(
        { case NewBlockHashes(hashes) => RLPList(hashes.map(_.toRLPEncodable): _*) },
        { case rlpList: RLPList => NewBlockHashes(rlpList.items.map(RLPDecoder.decode[BlockHashAndNumber])) }
      )
  }

  final case class GetBlockHeaders(
      requestId: RequestId,
      block: Either[BlockNumber, BlockHash],
      limit: Int,
      skip: Int,
      reverse: Boolean
  ) extends Encode[GetBlockHeaders]
      with RequestMessage {
    val code: Int = Codes.GetBlockHeadersCode

    override def toString: String =
      "GetBlockHeaders{ " +
        s"$requestId " +
        s"block: ${block.fold(a => a, b => b.toHex)} " +
        s"limit: $limit " +
        s"skip: $skip " +
        s"reverse: $reverse " +
        "}"
  }

  object GetBlockHeaders {
    def apply(block: Either[BlockNumber, BlockHash], limit: Int, skip: Int, reverse: Boolean): GetBlockHeaders =
      GetBlockHeaders(deriveRequestId(Seq(block, limit, skip, reverse)), block, limit, skip, reverse)

    implicit val rlpCodec: RLPCodec[GetBlockHeaders] =
      RLPCodec.instance(
        { case GetBlockHeaders(requestId, block, limit, skip, reverse) =>
          RLPList(
            requestId,
            RLPList(block.fold[RLPEncodeable](toEncodeable, toEncodeable), limit, skip, if (reverse) 1 else 0)
          )
        },
        {
          case RLPList(requestId, RLPList((block: RLPValue), limit, skip, reverse)) if block.bytes.length < 32 =>
            GetBlockHeaders(
              requestId.decodeAs[RequestId]("requestId"),
              Left(BlockNumber(block)),
              limit,
              skip,
              (reverse: Int) == 1
            )
          case RLPList(requestId, RLPList((block: RLPValue), limit, skip, reverse)) =>
            GetBlockHeaders(
              requestId.decodeAs[RequestId]("requestId"),
              Right(BlockHash(block)),
              limit,
              skip,
              (reverse: Int) == 1
            )
        }
      )
  }

  final case class BlockBodies(requestId: RequestId, bodies: Seq[ObftBody])
      extends Encode[BlockBodies]
      with ResponseMessage {
    val code: Int                      = Codes.BlockBodiesCode
    override def toShortString: String = s"BlockBodies { $requestId bodies: ${bodies.map(_.toShortString)} }"
  }

  object BlockBodies {
    implicit val rlpCodec: RLPCodec[BlockBodies] =
      RLPCodec.instance(
        { case BlockBodies(requestId, bodies) => RLPList(requestId, toRlpList(bodies)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          BlockBodies(requestId.decodeAs[RequestId]("requestId"), fromRlpList[ObftBody](rlpList))
        }
      )
  }

  final case class BlockHeaders(requestId: RequestId, headers: Seq[ObftHeader])
      extends Encode[BlockHeaders]
      with ResponseMessage {
    val code: Int = Codes.BlockHeadersCode
    override def toShortString: String =
      s"BlockHeaders { $requestId headers: ${headers.map(_.hash)} }"
  }

  object BlockHeaders {
    implicit val rlpCodec: RLPCodec[BlockHeaders] =
      RLPCodec.instance(
        { case BlockHeaders(requestId, headers) => RLPList(requestId, toRlpList(headers)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          BlockHeaders(requestId.decodeAs[RequestId]("requestId"), fromRlpList[ObftHeader](rlpList))
        }
      )
  }

  final case class GetBlockBodies(requestId: RequestId, hashes: Seq[BlockHash])
      extends Encode[GetBlockBodies]
      with RequestMessage {
    val code: Int                 = Codes.GetBlockBodiesCode
    override def toString: String = s"GetBlockBodies { $requestId hashes: $hashes }"
  }

  object GetBlockBodies {
    def apply(hashes: Seq[BlockHash]): GetBlockBodies =
      GetBlockBodies(deriveRequestId(hashes), hashes)

    implicit val rlpCodec: RLPCodec[GetBlockBodies] =
      RLPCodec.instance(
        { case GetBlockBodies(requestId, hashes) => RLPList(requestId, toRlpList(hashes)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          GetBlockBodies(requestId.decodeAs[RequestId]("requestId"), fromRlpList[BlockHash](rlpList))
        }
      )
  }

  // old pv63 start

  final case class GetNodeData(requestId: RequestId, mptElementsHashes: Seq[ByteString])
      extends Encode[GetNodeData]
      with RequestMessage {
    val code: Int = Codes.GetNodeDataCode
    override def toString: String =
      s"GetNodeData{ $requestId hashes: ${mptElementsHashes.map(e => Hex.toHexString(e.toArray[Byte]))} }"
    override def toShortString: String =
      s"GetNodeData{ $requestId hashes: <${mptElementsHashes.size} state tree hashes> }"
  }

  object GetNodeData {
    def apply(mptElementsHashes: Seq[ByteString]): GetNodeData =
      GetNodeData(deriveRequestId(mptElementsHashes), mptElementsHashes)

    implicit val rlpCodec: RLPCodec[GetNodeData] =
      RLPCodec.instance(
        { case GetNodeData(requestId, mptElementsHashes) => RLPList(requestId, toRlpList(mptElementsHashes)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          GetNodeData(requestId.decodeAs[RequestId]("requestId"), fromRlpList[ByteString](rlpList))
        }
      )
  }
  final case class NodeData(requestId: RequestId, values: Seq[ByteString])
      extends Encode[NodeData]
      with ResponseMessage {
    override val code: Int = Codes.NodeDataCode
    override def toString: String =
      s"NodeData{ $requestId values: ${values.map(b => Hex.toHexString(b.toArray[Byte]))} }"
    override def toShortString: String = s"NodeData{ $requestId values: <${values.size} state tree values> }"

    // FIXME: values should just be a Seq[MptNode]?
    @throws[RLPException]
    def getMptNode(index: Int): MptNode = {
      import io.iohk.scevm.mpt.MptNodeEncoders._
      values(index).toArray[Byte].toMptNode
    }
  }

  object NodeData {
    implicit val rlpCodec: RLPCodec[NodeData] =
      RLPCodec.instance(
        { case NodeData(requestId, values) => RLPList(requestId, toRlpList(values)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          NodeData(requestId.decodeAs[RequestId]("requestId"), fromRlpList[ByteString](rlpList))
        }
      )
  }

  final case class GetReceipts(requestId: RequestId, blockHashes: Seq[BlockHash])
      extends Encode[GetReceipts]
      with RequestMessage {
    val code: Int = Codes.GetReceiptsCode
    override def toString: String =
      s"GetReceipts{ $requestId blockHashes: ${blockHashes
        .map(blockhash => Hex.toHexString(blockhash.byteString.toArray[Byte]))} } "
  }

  object GetReceipts {
    def apply(blockHashes: Seq[BlockHash]): GetReceipts =
      GetReceipts(deriveRequestId(blockHashes), blockHashes)

    implicit val rlpCodec: RLPCodec[GetReceipts] =
      RLPCodec.instance(
        { case GetReceipts(requestId, blockHashes) => RLPList(requestId, toRlpList(blockHashes)) },
        { case RLPList(requestId, rlpList: RLPList) =>
          GetReceipts(requestId.decodeAs[RequestId]("requestId"), fromRlpList[BlockHash](rlpList))
        }
      )
  }

  final case class Receipts(requestId: RequestId, receiptsForBlocks: Seq[Seq[Receipt]])
      extends Encode[Receipts]
      with ResponseMessage {
    val code: Int = Codes.ReceiptsCode
    override def toShortString: String =
      s"Receipts { $requestId receiptsForBlocks: <${receiptsForBlocks.map(_.size).sum} receipts across ${receiptsForBlocks.size} blocks> }"
  }

  object Receipts {
    import Receipt.ReceiptRLPImplicits._
    import TypedTransaction._

    implicit val rlpCodec: RLPCodec[Receipts] =
      RLPCodec.instance(
        { case Receipts(requestId, receiptsForBlocks) =>
          RLPList(requestId, toRlpList(receiptsForBlocks.map(rs => toRlpList(rs.map(_.toRLPEncodable)))))
        },
        { case RLPList(requestId, rlpList: RLPList) =>
          Receipts(
            requestId.decodeAs[RequestId]("requestId"),
            rlpList.items.collect { case r: RLPList => r.items.toTypedRLPEncodables.map(_.toReceipt) }
          )
        }
      )
  }

  // old pv64 start
  final case class Status(
      protocolVersion: Int,
      networkId: Int,
      chainId: ChainId,
      stableHash: BlockHash,
      genesisHash: BlockHash,
      forkId: ForkId,
      stabilityParameter: Int,
      slotDuration: FiniteDuration,
      modeConfigHash: ByteString
  ) extends Encode[Status]
      with PushMessage {
    val code: Int = Codes.StatusCode
    override def toString: String =
      "Status { " +
        s"protocolVersion: $protocolVersion, " +
        s"networkId: $networkId, " +
        s"bestHash: ${stableHash.toHex}, " +
        s"genesisHash: ${genesisHash.toHex}," +
        s"forkId: $forkId," +
        "}"
  }

  object Status {
    implicit val rlpCodec: RLPCodec[Status] =
      RLPCodec.instance(
        {
          case Status(
                protocolVersion,
                networkId,
                chainId,
                stableHash,
                genesisHash,
                forkId,
                stabilityParameter,
                slotDuration,
                sidechainConfigHash
              ) =>
            RLPList(
              protocolVersion,
              networkId,
              chainId.value,
              stableHash,
              genesisHash,
              forkId.toRLPEncodable,
              stabilityParameter,
              slotDuration.toMillis,
              sidechainConfigHash
            )
        },
        {
          case RLPList(
                protocolVersion,
                networkId,
                chainId,
                bestHash,
                genesisHash,
                forkId,
                stabilityParameter,
                slotDuration,
                sidechainConfigHash
              ) =>
            Status(
              protocolVersion,
              networkId,
              ChainId(chainId),
              BlockHash(bestHash),
              BlockHash(genesisHash),
              decode[ForkId](forkId),
              decode[Int](stabilityParameter),
              decode[Int](slotDuration).milli,
              sidechainConfigHash
            )
        }
      )
  }

  final case class NewBlock(block: ObftBlock) extends Encode[NewBlock] with PushMessage {
    val code: Int                      = Codes.NewBlockCode
    override def toString: String      = s"NewBlock { block: ${block.fullToString} }"
    override def toShortString: String = s"NewBlock { block.header: ${block.header.fullToString} }"
  }

  object NewBlock {
    implicit val rlpCodec: RLPCodec[NewBlock] =
      RLPCodec.instance(
        { case NewBlock(block) => RLPList(block) },
        { case RLPList(block: RLPList) => NewBlock(decode[ObftBlock](block)) }
      )
  }

  /** Request full blocks starting from `blockHash` and moving backward `count` times
    * by getting the parent hash of every block.
    *
    * Semantically similar to `GetBlockHeaders(block = Right(blockHash), limit = count, skip = 0, reverse = true)`, but
    * returns full blocks instead of just headers.
    *
    * The blocks are returned in reversed order (from highest block number to lowest)
    *
    * @param requestId unique request id
    * @param blockHash the hash of the block from which to start the request
    * @param count number of blocks to request (`blockHash` inclusive)
    */
  final case class GetFullBlocks(requestId: RequestId, blockHash: BlockHash, count: Int)
      extends Encode[GetFullBlocks]
      with RequestMessage {
    val code: Int = Codes.GetFullBlocksCode

    override def toString: String =
      s"GetFullBlocks { requestId: $requestId, blockHash: $blockHash, count: $count }"
  }

  object GetFullBlocks {
    def apply(blockHash: BlockHash, count: Int): GetFullBlocks =
      GetFullBlocks(deriveRequestId(Seq(blockHash, count)), blockHash, count)

    implicit val rlpCodec: RLPCodec[GetFullBlocks] =
      RLPCodec.instance(
        { case GetFullBlocks(requestId, blockHash, count) => RLPList(requestId, blockHash, count) },
        { case RLPList(requestId, blockHash, count) =>
          GetFullBlocks(
            requestId.decodeAs[RequestId]("requestId"),
            blockHash.decodeAs[BlockHash]("blockHash"),
            count
          )
        }
      )
  }

  final case class FullBlocks(requestId: RequestId, blocks: Seq[ObftBlock])
      extends Encode[FullBlocks]
      with ResponseMessage {
    val code: Int = Codes.FullBlocksCode

    override def toString: String =
      (blocks.headOption, blocks.lastOption) match {
        case (Some(first), Some(last)) =>
          s"FullBlocks { num blocks: ${blocks.size}, first: ${first.header.idTag}, last: ${last.header.idTag} }"
        case _ =>
          s"FullBlocks { blocks: ${blocks.map(_.header.idTag)} }"
      }
  }

  object FullBlocks {

    implicit val rlpCodec: RLPCodec[FullBlocks] =
      RLPCodec.instance(
        { case FullBlocks(requestId, blocks) => RLPList(requestId, blocks) },
        { case RLPList(requestId, rlpList: RLPList) =>
          FullBlocks(requestId.decodeAs[RequestId]("requestId"), fromRlpList[ObftBlock](rlpList))
        }
      )
  }

  // Derive eth/66 request-ids using Scala's MurmurHash3, but with 64 bits
  // based on: https://gist.github.com/avnerbarr/82fb20d25c9b040f2caca8cdcefe7fc2/
  private def deriveRequestId(components: Seq[_]): RequestId =
    RequestId((components.hashCode().toLong << 32) + components.reverse.hashCode())
}
