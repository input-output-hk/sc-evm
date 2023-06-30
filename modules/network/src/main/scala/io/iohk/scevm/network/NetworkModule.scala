package io.iohk.scevm.network

import cats.effect.Async
import cats.syntax.all._
import fs2.concurrent.{SignallingRef, Topic}
import io.iohk.scevm.domain.ObftBlock

trait NetworkModule[F[_]] extends NewTransactionsListener[F] with BlockBroadcaster[F] {
  def peers: SignallingRef[F, Map[PeerId, PeerWithInfo]]

  def blocksFromNetwork: fs2.Stream[F, ObftBlock]

  def importTransaction(
      newTransactions: TransactionsImport.NewTransactions
  ): F[Unit]

  def branchFetcher: BranchFetcher[F]

  def mptNodeFetcher: MptNodeFetcher[F]

  def receiptsFetcher: ReceiptsFetcher[F]
}

class NetworkModuleImpl[F[_]: Async](
    peersStateRef: SignallingRef[F, Map[PeerId, PeerWithInfo]],
    syncConfig: SyncConfig,
    peerActionsTopic: Topic[F, PeerAction],
    transactionsImportService: TransactionsImportService[F],
    blocksTopic: Topic[F, ObftBlock],
    val branchFetcher: BranchFetcher[F],
    val mptNodeFetcher: MptNodeFetcher[F],
    val receiptsFetcher: ReceiptsFetcher[F]
) extends NetworkModule[F] {
  override def peers: SignallingRef[F, Map[PeerId, PeerWithInfo]] = peersStateRef

  override def broadcastBlock(block: ObftBlock): F[Unit] =
    fs2.Stream
      .eval(block.pure[F])
      .zip(peersStateRef.continuous.map(_.values.map(_.peer).toSet))
      .through(BlockBroadcast.toPeerActions[F])
      .evalTap(peerActionsTopic.publish1)
      .compile
      .drain

  override def blocksFromNetwork: fs2.Stream[F, ObftBlock] =
    blocksTopic.subscribe(syncConfig.messageSourceBuffer)

  override def importTransaction(
      newTransactions: TransactionsImport.NewTransactions
  ): F[Unit] =
    fs2.Stream
      .evalSeq(transactionsImportService.importTransaction(newTransactions).map(_.toList))
      .zip(peersStateRef.continuous)
      .through(TransactionsGossip.gossipToAll[F])
      .evalTap(peerActionsTopic.publish1)
      .compile
      .drain
}
