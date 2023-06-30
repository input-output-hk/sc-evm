package io.iohk.scevm.sidechain.consensus

import cats.effect.Sync
import cats.syntax.all._
import com.softwaremill.quicklens._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.consensus.pos.ObftBlockExecution
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.domain.{Address, ObftHeader, Token, TransactionLogEntry, UInt256}
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.sidechain.BridgeContract.IncomingTransactionHandledEvent
import io.iohk.scevm.sidechain.{
  BridgeContract,
  IncomingCrossChainTransactionsProvider,
  ValidIncomingCrossChainTransaction
}
import io.iohk.scevm.trustlesssidechain.cardano.MainchainTxHash

class IncomingCrossChainTransactionsValidator[F[_]: Sync](
    transactionsProvider: IncomingCrossChainTransactionsProvider[F],
    bridgeAddress: Address,
    conversionRate: UInt256
) extends PostExecutionValidator[F] {

  override def validate(
      initialState: WorldType,
      header: ObftHeader,
      result: ObftBlockExecution.BlockExecutionResult
  ): F[Either[PostExecutionValidator.PostExecutionError, Unit]] =
    transactionsProvider.getTransactions(initialState).map { transactions =>
      val logs = result.receipts.flatMap(_.logs)
      matchLogsWithMainchainTransactions(
        logs,
        transactions.modify(_.each.value).using(v => Token(v.value * conversionRate))
      ).leftMap(PostExecutionError(header.hash, _))
    }

  private def matchLogsWithMainchainTransactions(
      logs: Seq[TransactionLogEntry],
      mainchainTxs: Seq[ValidIncomingCrossChainTransaction]
  ): Either[String, Unit] = for {
    fromLogs <- logs.traverse(logEntryToIncomingTx).map(_.flatten)
    _        <- compare(fromLogs, mainchainTxs.map(CrossChainTransactionToCompare.apply))
  } yield ()

  private case class CrossChainTransactionToCompare(recipient: Address, value: Token, txId: MainchainTxHash)
  private object CrossChainTransactionToCompare {
    def apply(t: ValidIncomingCrossChainTransaction): CrossChainTransactionToCompare =
      CrossChainTransactionToCompare(t.recipient, t.value, t.txId)
  }
  private def logEntryToIncomingTx(
      e: TransactionLogEntry
  ): Either[String, Option[CrossChainTransactionToCompare]] =
    if (BridgeContract.isIncomingTransactionHandledEvent(bridgeAddress, e)) {
      val event = IncomingTransactionHandledEvent.decodeFromLogEntry(e)
      event.txId.toTxHash.bimap(
        msg => s"Invalid IncomingTransactionHandledEvent data: '${Hex.toHexString(e.data)}', $msg",
        txId => CrossChainTransactionToCompare(event.recipient, Token(event.amount.toBigInt), txId).some
      )
    } else Right(None)

  private def compare(
      fromSidechain: Seq[CrossChainTransactionToCompare],
      fromMainchain: Seq[CrossChainTransactionToCompare]
  ): Either[String, Unit] = {
    val fromMainchainPrefix = fromMainchain.take(fromSidechain.size)
    if (fromSidechain == fromMainchainPrefix) ().asRight
    else {
      // Should we spend computing power on making log message or just report that for given block hash there is mismatch?
      val sidechainIndexed    = fromSidechain.zipWithIndex.toSet
      val mainchainIndexed    = fromMainchainPrefix.zipWithIndex.toSet
      val sidechainNotMatched = sidechainIndexed.diff(mainchainIndexed)
      val mainchainNotMatched = mainchainIndexed.diff(sidechainIndexed)

      def render(set: Set[(CrossChainTransactionToCompare, Int)]) =
        set.toList
          .map { case (tx, idx) => (idx, tx.txId) }
          .sortBy(_._1)
          .mkString_("[", ", ", "]")

      show"The block transactions without a match: ${render(sidechainNotMatched)}, mainchain transactions without a match ${render(mainchainNotMatched)}".asLeft
    }
  }
}
