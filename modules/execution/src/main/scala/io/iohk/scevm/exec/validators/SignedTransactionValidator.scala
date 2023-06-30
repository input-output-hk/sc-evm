package io.iohk.scevm.exec.validators

import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain._

trait SignedTransactionValidator {

  /** Complete validation of transactions done before executing a transaction
    *  Initial tests of intrinsic validity stated in Section 6 of YP
    *
    * @param stx               Transaction to validate
    * @param senderAccount     Account of the sender of the tx
    * @param blockNumber       Container block
    * @param accumGasUsed      Total amount of gas spent prior this transaction within the container block
    * @return                  Transaction if valid, error otherwise
    */
  def validate(
      stx: SignedTransaction,
      senderAccount: Account,
      blockNumber: BlockNumber,
      gasLimit: BigInt,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid]

  /** Relaxed transaction validation performed before adding a transaction into the mempool
    *
    * @param stx               Transaction to validate
    * @param senderAccount     Account of the sender of the tx
    * @param blockNumber       Container block
    * @param accumGasUsed      Total amount of gas spent prior this transaction within the container block
    * @return                  Transaction if valid, error otherwise
    */
  def validateForMempool(
      stx: SignedTransaction,
      senderAccount: Account,
      blockNumber: BlockNumber,
      gasLimit: BigInt,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid]
}

sealed trait SignedTransactionError

object SignedTransactionError {
  final case object TransactionSignatureError extends SignedTransactionError
  final case class TransactionSyntaxError(reason: String) extends SignedTransactionError {
    override def toString: String = s"Error with the transaction syntax: $reason."
  }
  final case class TransactionAdditionToMempoolError(reason: String) extends SignedTransactionError {
    override def toString: String = s"Error adding the transaction to the transaction's pool: $reason."
  }
  final case class TransactionNonceError(txNonce: Nonce, senderNonce: Nonce) extends SignedTransactionError {
    override def toString: String =
      s"Invalid transaction nonce: received transaction nonce ${txNonce.value} but account nonce is: ${senderNonce.value}."
  }
  final case class TransactionNotEnoughGasForIntrinsicError(txGasLimit: BigInt, txIntrinsicGas: BigInt)
      extends SignedTransactionError {
    override def toString: String =
      s"Insufficient transaction gas limit: Transaction gas limit ($txGasLimit) < transaction intrinsic gas ($txIntrinsicGas)."
  }
  final case class TransactionSenderCantPayUpfrontCostError(upfrontCost: UInt256, senderBalance: UInt256)
      extends SignedTransactionError {
    override def toString: String =
      s"Insufficient balance: transaction's Upfrontcost ($upfrontCost) > sender balance ($senderBalance)."
  }
  final case class TransactionGasLimitTooBigError(txGasLimit: BigInt, accumGasUsed: BigInt, blockGasLimit: BigInt)
      extends SignedTransactionError {
    override def toString: String =
      s"Invalid transaction gas limit: Transaction gas limit ($txGasLimit) + gas accum ($accumGasUsed) > block gas limit ($blockGasLimit)."
  }
}

sealed trait SignedTransactionValid
case object SignedTransactionValid extends SignedTransactionValid
