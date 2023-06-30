package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.Concurrent
import fs2.concurrent.Topic
import io.iohk.scevm.domain.SignedTransaction

object TransactionsImport {

  def createTopic[F[_]: Concurrent]: F[Topic[F, NewTransactions]] = Topic[F, NewTransactions]

  sealed trait NewTransactions extends Product {
    def signedTransactions: NonEmptyList[SignedTransaction]
    def fromOpt: Option[PeerId]
    def withTransactions(signedTransactions: NonEmptyList[SignedTransaction]): NewTransactions
  }
  final case class TransactionsFromRpc(signedTransactions: NonEmptyList[SignedTransaction]) extends NewTransactions {
    override def toString: String = s"TransactionsFromRpc(${signedTransactions.map(_.hash)})"

    override def fromOpt: Option[PeerId] = None

    override def withTransactions(signedTransactions: NonEmptyList[SignedTransaction]): NewTransactions =
      copy(signedTransactions)
  }
  final case class TransactionsFromPeer(from: PeerId, signedTransactions: NonEmptyList[SignedTransaction])
      extends NewTransactions {
    override def toString: String = s"TransactionsFromPeer($from, ${signedTransactions.map(_.hash)})"

    override def fromOpt: Option[PeerId] = Some(from)

    override def withTransactions(signedTransactions: NonEmptyList[SignedTransaction]): NewTransactions =
      copy(signedTransactions = signedTransactions)
  }

}
