package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.{Async, Sync}
import cats.implicits.toFunctorOps
import fs2.Pipe
import io.iohk.scevm.domain.SignedTransaction
import io.iohk.scevm.metrics.Fs2Monitoring
import io.iohk.scevm.network.PeerAction.MessageToPeer
import io.iohk.scevm.network.TransactionsImport.TransactionsFromPeer
import io.iohk.scevm.network.p2p.messages.OBFT1.{
  GetPooledTransactions,
  NewPooledTransactionHashes,
  PooledTransactions,
  SignedTransactions
}
import io.iohk.scevm.storage.execution.SlotBasedMempool
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

object TransactionsHandler {

  /** Handles transaction related messages */
  def pipe[F[_]: Async](
      mempool: SlotBasedMempool[F, SignedTransaction],
      transactionsImportService: TransactionsImportService[F],
      peersState: fs2.Stream[F, Map[PeerId, PeerWithInfo]]
  ): Pipe[F, PeerEvent, MessageToPeer] = {
    implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(TransactionsHandler.getClass)
    _.filter(isTransactionRelated)
      .through(Fs2Monitoring.probe("transactions_handler"))
      .broadcastThrough(
        requestMissingTransactions(mempool),
        importTransactionsResponse(
          transactionsImportService,
          peersState
        ),
        exchangePoolOnHandshake(mempool)
      )
  }

  private def isTransactionRelated(peerEvent: PeerEvent): Boolean =
    peerEvent match {
      case PeerEvent.MessageFromPeer(_, NewPooledTransactionHashes(_)) => true
      case PeerEvent.MessageFromPeer(_, PooledTransactions(_, _))      => true
      case PeerEvent.MessageFromPeer(_, SignedTransactions(_))         => true
      case _                                                           => false
    }

  private def requestMissingTransactions[F[_]: Async](
      mempool: SlotBasedMempool[F, SignedTransaction]
  ): Pipe[F, PeerEvent, MessageToPeer] =
    _.collect { case PeerEvent.MessageFromPeer(peerId, NewPooledTransactionHashes(hashes)) =>
      for {
        poolTxs   <- mempool.getAll
        missingTxs = hashes.diff(poolTxs.map(_.hash))
      } yield MessageToPeer(peerId, GetPooledTransactions(missingTxs))
    }.flatMap(fs2.Stream.eval)

  /** Import a transaction and push it to the gossip stream or drop it if it already exists in the mempool */
  private def importTransactionsResponse[F[_]: Sync: Logger](
      transactionsImportService: TransactionsImportService[F],
      peersState: fs2.Stream[F, Map[PeerId, PeerWithInfo]]
  ): Pipe[F, PeerEvent, MessageToPeer] =
    _.collect {
      case PeerEvent.MessageFromPeer(peerId, PooledTransactions(_, txs)) => (peerId, txs)
      case PeerEvent.MessageFromPeer(peerId, SignedTransactions(txs))    => (peerId, txs)
    }.flatMap { case (peerId, txs) =>
      NonEmptyList.fromList(txs.toList) match {
        case Some(transactions) =>
          fs2.Stream
            .evalSeq(
              transactionsImportService.importTransaction(TransactionsFromPeer(peerId, transactions)).map(_.toList)
            )
            .zip(peersState)
            .through(TransactionsGossip.gossipToAll[F])
        case None => fs2.Stream.empty
      }
    }

  private def exchangePoolOnHandshake[F[_]: Async](
      mempool: SlotBasedMempool[F, SignedTransaction]
  ): Pipe[F, PeerEvent, MessageToPeer] =
    _.collect { case PeerEvent.PeerHandshakeSuccessful(peer, _) =>
      for {
        stxs  <- mempool.getAll
        hashes = stxs.map(_.hash)
      } yield MessageToPeer(peer.id, NewPooledTransactionHashes(hashes))
    }.flatMap(fs2.Stream.eval)

}
