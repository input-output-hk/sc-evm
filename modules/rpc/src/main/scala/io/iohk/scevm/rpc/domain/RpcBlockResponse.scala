package io.iohk.scevm.rpc.domain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, ObftBlock}
import io.iohk.scevm.rpc.domain.RpcBlockResponse.RpcSpecBlockResponse
import io.iohk.scevm.utils.SystemTime.TimestampSeconds

// ETS fails on additional fields so we are hiding them for now
// We should have a way to either make retesteth not check for additional fields, or have the node be able to
// show or hide them depending on the configuration
final case class RpcBlockResponse(
    baseFeePerGas: Option[BigInt],
    difficulty: BigInt = 0, // difficulty does not exist in PoS
    extraData: ByteString = Address(0).bytes,
    gasLimit: BigInt,
    gasUsed: BigInt,
    logsBloom: ByteString,
    miner: ByteString,
    mixHash: Option[ByteString] = None,
    nonce: Option[ByteString] = None,
    number: BlockNumber,
    parentHash: BlockHash,
    receiptsRoot: ByteString,
    sha3Uncles: ByteString = RpcBlockResponse.EmptyHash,
    size: BigInt,
    stateRoot: ByteString,
    timestamp: TimestampSeconds,
    totalDifficulty: Option[BigInt] = Some(0), // difficulty does not exist in PoS
    transactions: Either[Seq[ByteString], Seq[RpcFullTransactionResponse]],
    transactionsRoot: ByteString,
    uncles: Seq[BlockHash] = Seq.empty, // the concept of uncles does not exist
    hash: Option[BlockHash]
    // publicSigningKey: ByteString,
    // slotNumber: Option[BigInt]
) extends RpcSpecBlockResponse

object RpcBlockResponse {

  val EmptyHash: ByteString = crypto.kec256(ByteString.empty)

  /** Fields required by the spec */
  private[scevm] trait RpcSpecBlockResponse {
    def baseFeePerGas: Option[BigInt]
    def difficulty: BigInt
    def extraData: ByteString
    def gasLimit: BigInt
    def gasUsed: BigInt
    def logsBloom: ByteString
    def miner: ByteString
    def mixHash: Option[ByteString]
    def nonce: Option[ByteString]
    def number: BlockNumber
    def parentHash: BlockHash
    def receiptsRoot: ByteString
    def sha3Uncles: ByteString
    def size: BigInt
    def stateRoot: ByteString
    def timestamp: TimestampSeconds
    def totalDifficulty: Option[BigInt]
    def transactions: Either[Seq[ByteString], Seq[RpcFullTransactionResponse]]
    def transactionsRoot: ByteString
    def uncles: Seq[BlockHash]
  }

  def from(
      block: ObftBlock,
      chainId: ChainId,
      fullTxs: Boolean,
      pending: Boolean = false
  ): Either[JsonRpcError, RpcBlockResponse] = {
    // Transactions can be an Array of 32 Bytes transaction hashes or an Array of transaction objects
    val transactionsOrErrors: Either[JsonRpcError, Either[Seq[ByteString], Seq[RpcFullTransactionResponse]]] =
      if (fullTxs) {
        import cats.syntax.traverse._

        block.body.transactionList.zipWithIndex
          .traverse { case (signedTransaction, transactionIndex) =>
            RpcFullTransactionResponse.from(
              signedTransaction,
              BigInt(transactionIndex),
              block.hash,
              block.number,
              chainId
            )
          }
          .map(Right(_))
      } else {
        Right(Left(block.body.transactionList.map(_.hash.byteString)))
      }

    transactionsOrErrors.map { transactions =>
      RpcBlockResponse(
        baseFeePerGas = None,
        gasLimit = block.header.gasLimit,
        gasUsed = block.header.gasUsed,
        logsBloom = block.header.logsBloom,
        number = block.number,
        parentHash = block.header.parentHash,
        receiptsRoot = block.header.receiptsRoot,
        miner = block.header.beneficiary.bytes,
        size = block.size,
        stateRoot = block.header.stateRoot,
        timestamp = block.header.unixTimestamp.toTimestampSeconds,
        transactions = transactions,
        transactionsRoot = block.header.transactionsRoot,
        hash = Option.when(!pending)(block.hash)
        // publicSigningKey = block.header.publicSigningKey,
        // slotNumber = Some(block.header.slotNumber.number)
      )
    }
  }
}
