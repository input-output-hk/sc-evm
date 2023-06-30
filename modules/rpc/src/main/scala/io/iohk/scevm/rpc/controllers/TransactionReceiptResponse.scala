package io.iohk.scevm.rpc.controllers

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.TransactionOutcome.{FailureOutcome, HashOutcome, SuccessOutcome}
import io.iohk.scevm.domain.{
  Address,
  BlockHash,
  BlockNumber,
  ContractAddress,
  ObftHeader,
  Receipt,
  SignedTransaction,
  TransactionHash
}

/**  Params docs copied from - https://eth.wiki/json-rpc/API
  *  @param transactionHash DATA, 32 Bytes - hash of the transaction.
  *  @param transactionIndex QUANTITY - integer of the transactions index position in the block.
  *  @param blockNumber QUANTITY - block number where this transaction was in.
  *  @param blockHash DATA, 32 Bytes - hash of the block where this transaction was in.
  *  @param from DATA, 20 Bytes - address of the sender.
  *  @param to DATA, 20 Bytes - address of the receiver. None when it's a contract creation transaction.
  *  @param cumulativeGasUsed QUANTITY  - The total amount of gas used when this transaction was executed in the block.
  *  @param gasUsed QUANTITY  - The amount of gas used by this specific transaction alone.
  *  @param contractAddress DATA, 20 Bytes - The contract address created, if the transaction was a contract creation, otherwise None.
  *  @param logs Array - Array of log objects, which this transaction generated.
  *  @param logsBloom DATA, 256 Bytes - Bloom filter for light clients to quickly retrieve related logs.
  *  @param root DATA 32 bytes of post-transaction state root (pre Byzantium, otherwise None)
  *  @param status QUANTITY either 1 (success) or 0 (failure) (post Byzantium, otherwise None)
  */
final case class TransactionReceiptResponse(
    transactionHash: TransactionHash,
    transactionIndex: BigInt,
    blockNumber: BlockNumber,
    blockHash: BlockHash,
    from: Address,
    to: Option[Address],
    cumulativeGasUsed: BigInt,
    gasUsed: BigInt,
    contractAddress: Option[Address],
    logs: Seq[TransactionLog],
    logsBloom: ByteString,
    root: Option[ByteString],
    status: Option[BigInt]
)

object TransactionReceiptResponse {

  // scalastyle:off method.length
  def apply(
      receipt: Receipt,
      signedTx: SignedTransaction,
      signedTransactionSender: Address,
      transactionIndex: Int,
      obftHeader: ObftHeader,
      gasUsedByTransaction: BigInt
  ): TransactionReceiptResponse = {
    val contractAddress = if (signedTx.transaction.isContractInit) {
      //do not subtract 1 from nonce because in transaction we have nonce of account before transaction execution
      Some(ContractAddress.fromSender(signedTransactionSender, signedTx.transaction.nonce))
    } else None

    val txLogs = receipt.logs.zipWithIndex.map { case (txLog, index) =>
      TransactionLog(
        logIndex = index,
        transactionIndex = transactionIndex,
        transactionHash = signedTx.hash,
        blockHash = obftHeader.hash,
        blockNumber = obftHeader.number,
        address = txLog.loggerAddress,
        data = txLog.data,
        topics = txLog.logTopics
      )
    }

    val (root, status) = receipt.postTransactionStateHash match {
      case FailureOutcome         => (None, Some(BigInt(0)))
      case SuccessOutcome         => (None, Some(BigInt(1)))
      case HashOutcome(stateHash) => (Some(stateHash), None)
    }

    new TransactionReceiptResponse(
      transactionHash = signedTx.hash,
      transactionIndex = transactionIndex,
      blockNumber = obftHeader.number,
      blockHash = obftHeader.hash,
      from = signedTransactionSender,
      to = signedTx.transaction.receivingAddress,
      cumulativeGasUsed = receipt.cumulativeGasUsed,
      gasUsed = gasUsedByTransaction,
      contractAddress = contractAddress,
      logs = txLogs,
      logsBloom = receipt.logsBloomFilter,
      root = root,
      status = status
    )
  }
  // scalastyle:on method.length
}

final case class TransactionLog(
    logIndex: BigInt,
    transactionIndex: BigInt,
    transactionHash: TransactionHash,
    blockHash: BlockHash,
    blockNumber: BlockNumber,
    address: Address,
    data: ByteString,
    topics: Seq[ByteString]
)
