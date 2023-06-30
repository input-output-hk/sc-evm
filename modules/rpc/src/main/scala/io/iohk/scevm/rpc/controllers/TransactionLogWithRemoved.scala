package io.iohk.scevm.rpc.controllers

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, ObftBlock, TransactionHash, TransactionLogEntry}

final case class TransactionLogWithRemoved(
    removed: Boolean,
    logIndex: BigInt,
    transactionIndex: BigInt,
    transactionHash: TransactionHash,
    blockHash: BlockHash,
    blockNumber: BlockNumber,
    address: Address,
    data: ByteString,
    topics: Seq[ByteString]
)

object TransactionLogWithRemoved {
  def apply(
      block: ObftBlock,
      log: TransactionLogEntry,
      index: BigInt,
      receiptIndex: Int
  ): TransactionLogWithRemoved =
    TransactionLogWithRemoved(
      removed = false,
      index,
      BigInt(receiptIndex),
      block.body.transactionList(receiptIndex).hash,
      block.hash,
      block.number,
      log.loggerAddress,
      log.data,
      log.logTopics
    )
}
