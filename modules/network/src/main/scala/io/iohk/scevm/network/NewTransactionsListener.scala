package io.iohk.scevm.network

trait NewTransactionsListener[F[_]] {
  def importTransaction(
      newTransactions: TransactionsImport.NewTransactions
  ): F[Unit]
}
