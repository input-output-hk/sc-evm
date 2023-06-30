package io.iohk.scevm.cardanofollower.datasource.mock

import cats.implicits.showInterpolator
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig
import io.iohk.scevm.cardanofollower.plutus.TransactionRecipientDatum
import io.iohk.scevm.domain.{Address, Token}
import io.iohk.scevm.sidechain.IncomingCrossChainTransaction
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainSlot, MainchainTxHash}

import scala.util.Random

import IncomingTransactionGeneratorImpl.IndexedIncomingTransaction

trait IncomingTransactionGenerator {
  def generateIncomingTransactions(
      after: Option[MainchainTxHash],
      at: MainchainSlot
  ): List[IncomingCrossChainTransaction]

  def getNumberOfTransactions(mainchainSlot: MainchainSlot): Int
}

class IncomingTransactionGeneratorImpl private (indexedTransactions: List[IndexedIncomingTransaction])
    extends IncomingTransactionGenerator {

  override def generateIncomingTransactions(
      after: Option[MainchainTxHash],
      at: MainchainSlot
  ): List[IncomingCrossChainTransaction] = {
    val lowerTransactionIndex = after match {
      case Some(hash) =>
        indexedTransactions
          .find(_.transaction.txId == hash)
          .getOrElse(throw new Exception(show"Incoming transaction $hash doesn't exist"))
          .index
      case None => -1
    }

    indexedTransactions
      .filter(tx => tx.index > lowerTransactionIndex && tx.mainchainSlot.number <= at.number)
      .map(_.transaction)
  }

  override def getNumberOfTransactions(mainchainSlot: MainchainSlot): Int =
    indexedTransactions.count(_.mainchainSlot == mainchainSlot)
}

object IncomingTransactionGeneratorImpl {

  final private case class IndexedIncomingTransaction(
      transaction: IncomingCrossChainTransaction,
      mainchainSlot: MainchainSlot,
      index: Int
  )

  def apply(
      numberOfMainchainSlot: Int,
      mockConfig: DatasourceConfig.Mock,
      sidechainAddresses: List[Address]
  ): IncomingTransactionGeneratorImpl = {
    val random = new Random(mockConfig.seed)

    val incomingTransactionsWithMainchainSlot = (0 until numberOfMainchainSlot).toList.flatMap { i =>
      val mainchainSlot = MainchainSlot(i)
      val transactions  = generateIncomingTransactions(mockConfig, random, mainchainSlot, sidechainAddresses)
      transactions.map(tx => (tx, mainchainSlot))
    }

    val indexedIncomingTransaction = incomingTransactionsWithMainchainSlot.zipWithIndex.map {
      case ((tx, mainchainSlot), index) => IndexedIncomingTransaction(tx, mainchainSlot, index)
    }

    new IncomingTransactionGeneratorImpl(indexedIncomingTransaction)
  }

  private def generateIncomingTransactions(
      mockConfig: DatasourceConfig.Mock,
      random: Random,
      mainchainSlot: MainchainSlot,
      sidechainAddresses: List[Address]
  ): List[IncomingCrossChainTransaction] = {
    val numberOfTransactions = random.between(0, mockConfig.maxNumberOfTransactionsPerBlock)

    (0 until numberOfTransactions).map { _ =>
      val recipient       = sidechainAddresses(random.nextInt(sidechainAddresses.length))
      val token           = random.between(mockConfig.minTokenAmount, mockConfig.maxTokenAmount)
      val transactionHash = generateTransactionHash(random)
      // In a real network, not all mainchain slot will have a block. It's just to have an increasing block number
      val mainchainBlockNumber = MainchainBlockNumber(mainchainSlot.number)

      IncomingCrossChainTransaction(
        TransactionRecipientDatum(recipient),
        Token(token),
        transactionHash,
        mainchainBlockNumber
      )
    }.toList
  }

  private def generateTransactionHash(random: Random): MainchainTxHash = {
    val arr = Array.ofDim[Byte](32)
    random.nextBytes(arr)
    val hash = arr.iterator.map(b => String.format("%02x", b)).mkString("")
    MainchainTxHash.decodeUnsafe(hash)
  }
}
