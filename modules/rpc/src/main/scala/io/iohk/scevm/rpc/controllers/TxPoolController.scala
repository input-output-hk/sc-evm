package io.iohk.scevm.rpc.controllers

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{Address, BlockContext, BlockHash, BlockNumber, Nonce, SignedTransaction, Token}
import io.iohk.scevm.ledger.TransactionFilter
import io.iohk.scevm.rpc.controllers.TxPoolController.GetContentResponse
import io.iohk.scevm.rpc.domain._
import io.iohk.scevm.rpc.{AlwaysNull, ServiceResponseF}
import io.iohk.scevm.storage.execution.SlotBasedMempool

trait TxPoolController[F[_]] {
  def getContent(): ServiceResponseF[F, GetContentResponse]
}

object TxPoolController {
  final case class RpcPooledTransaction(
      accessList: Option[Seq[RpcAccessListItemResponse]],
      blockHash: BlockHash,
      blockNumber: AlwaysNull = AlwaysNull,
      from: Address,
      gas: BigInt,
      gasPrice: Option[BigInt],
      hash: ByteString,
      input: ByteString,
      maxFeePerGas: Option[BigInt],
      maxPriorityFeePerGas: Option[BigInt],
      nonce: Nonce,
      r: BigInt,
      s: BigInt,
      to: Option[ByteString],
      transactionIndex: AlwaysNull = AlwaysNull,
      `type`: Byte,
      v: Option[BigInt],
      value: Token
  )

  type TxsByNonce          = Map[Nonce, RpcPooledTransaction]
  type TxsBySenderAndNonce = Map[Address, TxsByNonce]

  final case class GetContentResponse(
      pending: TxsBySenderAndNonce,
      queued: TxsBySenderAndNonce
  )

  val defaultTransactionIndex: BigInt = BigInt(0)
  val defaultBlockHash: BlockHash     = BlockHash(ByteString(Array.fill(32)(0: Byte)))
  val defaultBlockNumber: BlockNumber = BlockNumber(0)

  def toPooledTransaction(tx: RpcFullTransactionResponse): RpcPooledTransaction = {
    val (gasPrice, maxFeePerGas, maxPriorityFeePerGas, accessList, v) = tx match {
      case tx: RpcLegacyTransactionResponse => (tx.gasPrice.some, None, None, None, tx.v.some)
      case tx: RpcSigned1559TransactionResponse =>
        (None, tx.maxFeePerGas.some, tx.maxPriorityFeePerGas.some, tx.accessList.some, None)
      case tx: RpcSigned2930TransactionResponse => (tx.gasPrice.some, None, None, tx.accessList.some, None)
    }
    RpcPooledTransaction(
      accessList = accessList,
      blockHash = defaultBlockHash,
      from = tx.from,
      gas = tx.gas,
      gasPrice = gasPrice,
      hash = tx.hash,
      input = tx.input,
      maxFeePerGas = maxFeePerGas,
      maxPriorityFeePerGas = maxPriorityFeePerGas,
      nonce = tx.nonce,
      r = tx.r,
      s = tx.s,
      to = tx.to,
      `type` = tx.`type`,
      v = v,
      value = Token(tx.value)
    )
  }

}

class TxPoolControllerImpl[F[_]: Sync](
    mempool: SlotBasedMempool[F, SignedTransaction],
    worldStateBuilder: WorldStateBuilder[F],
    currentBranch: CurrentBranch.Signal[F],
    blockchainConfig: BlockchainConfig
) extends TxPoolController[F] {

  import TxPoolController._

  override def getContent(): ServiceResponseF[F, GetContentResponse] =
    for {
      bestHeader   <- currentBranch.get.map(_.best)
      transactions <- mempool.getAll
      result <-
        worldStateBuilder.getWorldStateForBlock(bestHeader.stateRoot, BlockContext.from(bestHeader)).use { world =>
          Sync[F].pure {
            for {
              pendingTxs <-
                toTxsBySenderAndNonce(
                  TransactionFilter.getPendingTransactions(
                    blockchainConfig.chainId,
                    blockchainConfig.genesisData.accountStartNonce
                  )(transactions, world.getAccount)
                )
              queuedTxs <-
                toTxsBySenderAndNonce(
                  TransactionFilter.getQueuedTransactions(
                    blockchainConfig.chainId,
                    blockchainConfig.genesisData.accountStartNonce
                  )(transactions, world.getAccount)
                )
            } yield GetContentResponse(pendingTxs, queuedTxs)
          }
        }
    } yield result

  private def toTxsBySenderAndNonce(
      txs: Seq[SignedTransaction]
  ): Either[JsonRpcError, TxsBySenderAndNonce] =
    txs
      .traverse { tx =>
        // The transactions are not part of a block yet, so default values are used.
        RpcFullTransactionResponse
          .from(
            tx,
            defaultTransactionIndex,
            defaultBlockHash,
            defaultBlockNumber,
            blockchainConfig.chainId
          )
          .map(TxPoolController.toPooledTransaction)
      }
      .map(responseTxs => responseTxs.groupMapReduce(_.from)(tx => Map(tx.nonce -> tx))(_ ++ _))

}
