package io.iohk.scevm.ledger

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Account, Address, Nonce, SignedTransaction}

object TransactionFilter {

  private val keepPendingTransactions = (nonce: Nonce, signedTransactionsByIndex: Seq[(SignedTransaction, Int)]) =>
    signedTransactionsByIndex.takeWhile { case (stx, idx) => stx.transaction.nonce.value == nonce.value + idx }

  private val keepQueuedTransactions = (nonce: Nonce, signedTransactionsByIndex: Seq[(SignedTransaction, Int)]) =>
    signedTransactionsByIndex.dropWhile { case (stx, idx) => stx.transaction.nonce.value == nonce.value + idx }

  /** Filter transactions that can potentially be added to the next block and order them based on account nonce.
    *
    * @param signedTransactions all pooled transactions
    * @param accountProvider account to address mapping
    * @return transactions pending next block
    */
  def getPendingTransactions(chainId: ChainId, accountStartNonce: Nonce)(
      signedTransactions: Seq[SignedTransaction],
      accountProvider: Address => Option[Account]
  ): Seq[SignedTransaction] =
    getTransactions(chainId, accountStartNonce, signedTransactions, keepPendingTransactions, accountProvider)

  /** Return transactions that cannot yet be added to the next block, based on account nonce.
    *  Dual to `getPendingTransactions`
    *
    * @param signedTransactions all pooled transactions
    * @param accountProvider account to address mapping
    * @return transactions for possible future blocks after the next one
    */
  def getQueuedTransactions(chainId: ChainId, accountStartNonce: Nonce)(
      signedTransactions: Seq[SignedTransaction],
      accountProvider: Address => Option[Account]
  ): Seq[SignedTransaction] =
    getTransactions(chainId, accountStartNonce, signedTransactions, keepQueuedTransactions, accountProvider)

  private def getTransactions(
      chainId: ChainId,
      accountStartNonce: Nonce,
      signedTransactions: Seq[SignedTransaction],
      filterFunction: (Nonce, Seq[(SignedTransaction, Int)]) => Seq[(SignedTransaction, Int)],
      accountProvider: Address => Option[Account]
  ): Seq[SignedTransaction] = {
    val signedTransactionsByAccount: Map[Address, (Nonce, Seq[SignedTransaction])] =
      signedTransactions
        .flatMap { signedTransaction =>
          SignedTransaction
            .getSender(signedTransaction)(chainId)
            .map(sender => sender -> signedTransaction)
        }
        .groupMap(_._1)(_._2)
        .map { case (address, transactions) =>
          val nonce = accountProvider(address).map(_.nonce).getOrElse(accountStartNonce)
          address -> (nonce, transactions)
        }

    signedTransactionsByAccount.flatMap { case (_, (initialNonce, transactions)) =>
      filterSignedTransactions(transactions, initialNonce, filterFunction)
    }.toSeq
  }

  private def filterSignedTransactions(
      signedTransactions: Seq[SignedTransaction],
      startNonce: Nonce,
      filterFunction: (Nonce, Seq[(SignedTransaction, Int)]) => Seq[(SignedTransaction, Int)]
  ): Seq[SignedTransaction] = {
    val potentialSignedTransactionsWithIndex = signedTransactions
      .filter(stx => stx.transaction.nonce >= startNonce)
      .sortBy(_.transaction.nonce)
      .zipWithIndex

    filterFunction(startNonce, potentialSignedTransactionsWithIndex).map(_._1)
  }
}
