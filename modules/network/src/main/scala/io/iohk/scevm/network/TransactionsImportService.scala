package io.iohk.scevm.network

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import io.iohk.scevm.domain.{SignedTransaction, Slot}
import io.iohk.scevm.network.TransactionsImport.NewTransactions
import io.iohk.scevm.storage.execution.SlotBasedMempool
import org.typelevel.log4cats.{Logger, LoggerFactory, SelfAwareStructuredLogger}

trait TransactionsImportService[F[_]] {
  def importTransaction(newTransactions: NewTransactions): F[Option[NewTransactions]]
}

class TransactionsImportServiceImpl[F[_]: Monad: LoggerFactory](
    mempool: SlotBasedMempool[F, SignedTransaction],
    slotEvents: SignallingRef[F, Slot]
) extends TransactionsImportService[F] {

  override def importTransaction(newTransactions: NewTransactions): F[Option[NewTransactions]] = {
    implicit val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

    for {
      lastSlot  <- slotEvents.get
      additions <- mempool.addAll(newTransactions.signedTransactions.toList, lastSlot)
      importedTransactionsOpt <- additions
                                   .flatTraverse {
                                     case SlotBasedMempool.Added(stx, slot) =>
                                       Logger[F]
                                         .debug(show"${stx.hash} added to mempool at $slot")
                                         .as(List(stx))

                                     case SlotBasedMempool.NotAdded(stx, reason) =>
                                       Logger[F]
                                         .debug(show"${stx.hash} not added to mempool because $reason")
                                         .as(List.empty[SignedTransaction])
                                   }
                                   .map(NonEmptyList.fromList)
    } yield importedTransactionsOpt.map(newTransactions.withTransactions)

  }
}
