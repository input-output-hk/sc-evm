package io.iohk.scevm.rpc.controllers

import cats.Applicative
import cats.syntax.all._
import io.iohk.scevm.network.NewTransactionsListener
import io.iohk.scevm.network.TransactionsImport.NewTransactions

import scala.collection.mutable.ListBuffer

final case class NewTransactionListenerStub[F[_]: Applicative](observed: ListBuffer[NewTransactions] = ListBuffer.empty)
    extends NewTransactionsListener[F] {
  override def importTransaction(newTransactions: NewTransactions): F[Unit] =
    observed.addOne(newTransactions).pure[F].void
}
