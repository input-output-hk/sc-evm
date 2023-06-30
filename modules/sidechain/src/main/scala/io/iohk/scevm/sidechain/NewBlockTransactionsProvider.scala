package io.iohk.scevm.sidechain

import cats.Functor
import cats.data.EitherT
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.TransactionFilter
import io.iohk.scevm.sidechain.NewBlockTransactionsProvider.SlotBasedMempool
import io.iohk.scevm.sidechain.metrics.SidechainMetrics
import io.iohk.scevm.sync.NextBlockTransactions
import io.janstenpickle.trace4cats.inject.Trace

private class ValidatorNewBlockTransactionsProvider[F[_]: Trace: Sync, SignatureScheme <: AbstractSignatureScheme](
    mempool: SlotBasedMempool[F],
    worldStateBuilder: WorldStateBuilder[F],
    signTransactionProvider: HandoverTransactionProvider[F, SignatureScheme],
    metrics: SidechainMetrics[F],
    incomingTransactionService: IncomingTransactionsService[F],
    epochDerivation: SidechainEpochDerivation[F],
    filterPendingTransactions: (Seq[SignedTransaction], Address => Option[Account]) => Seq[SignedTransaction]
) extends NextBlockTransactions[F] {

  override def getNextTransactions(
      currentSlot: Slot,
      keySet: LeaderSlotEvent.KeySet,
      best: ObftHeader
  ): F[Either[String, List[SignedTransaction]]] =
    // We change the block context slot because we want the transactions at the time of
    // the current slot and not at the time of the previous best block.
    worldStateBuilder
      .getWorldStateForBlock(best.stateRoot, BlockContext.from(best).copy(slotNumber = currentSlot))
      .use { world =>
        val phase = epochDerivation.getSidechainEpochPhase(currentSlot)
        val epoch = epochDerivation.getSidechainEpoch(currentSlot)
        (for {
          crossChainPrvKey <- extractCrossChainPrivateKey(keySet)
          signTx           <- getSignTransaction(world, phase, epoch, crossChainPrvKey)
          crossChainTx     <- EitherT.right(getIncomingCrossChainTransactions(world))
          regularTx        <- EitherT.right[String](mempool.getAliveElements(currentSlot))
          signedSystemTxs =
            signTxs(
              world
                .getAccount(Address.fromPrivateKey(keySet.prvKey))
                .map(_.nonce)
                .getOrElse(world.accountStartNonce),
              keySet.prvKey,
              signTx.toList ++ crossChainTx
            )
        } yield signedSystemTxs ++ filterPendingTransactions(regularTx, world.getAccount)).value
      }

  private def extractCrossChainPrivateKey(
      keySet: LeaderSlotEvent.KeySet
  ): EitherT[F, String, SignatureScheme#PrivateKey] =
    EitherT((keySet.crossChainPrvKey match {
      case Some(value) => Right(value.asInstanceOf[SignatureScheme#PrivateKey])
      case None        => Left(s"This node is slot leader but no cross-chain private key found")
    }).pure[F])

  private def getSignTransaction(
      world: InMemoryWorldState,
      phase: EpochPhase,
      epoch: SidechainEpoch,
      crossChainPrivateKey: SignatureScheme#PrivateKey
  ) =
    phase match {
      case EpochPhase.Handover =>
        EitherT(signTransactionProvider.getSignTx(epoch, world, crossChainPrivateKey))
          .leftMap(err => s"Couldn't get handover signature transaction, because of '$err'")
          .map(_.some)
      case _ =>
        EitherT.fromEither(none[SystemTransaction].asRight)
    }

  private def getIncomingCrossChainTransactions(world: InMemoryWorldState) =
    incomingTransactionService
      .getTransactions(world)
      .flatTap(tx => metrics.incomingTransactionsCount.inc(tx.size))

  private def signTxs(startNonce: Nonce, privateKey: SidechainPrivateKey, transactions: List[SystemTransaction]) =
    transactions
      .zip(Iterator.iterate(startNonce)(_.increaseOne))
      .map { case (tx, nonce) =>
        SignedTransaction.sign(
          tx.toTransaction(nonce),
          privateKey,
          Some(tx.chainId)
        )
      }
}

private class PassiveNewBlockTransactionProvider[F[_]: Functor](
    slotBasedMempool: SlotBasedMempool[F]
) extends NextBlockTransactions[F] {
  override def getNextTransactions(
      slot: Slot,
      keySet: LeaderSlotEvent.KeySet,
      best: ObftHeader
  ): F[Either[String, List[SignedTransaction]]] =
    slotBasedMempool.getAliveElements(slot).map(Right(_))
}

object NewBlockTransactionsProvider {
  trait SlotBasedMempool[F[_]] {

    /** @return all transactions with a proper TTL for the corresponding slot from the mempool
      */
    def getAliveElements(slot: Slot): F[List[SignedTransaction]]
  }

  object SlotBasedMempool {
    def apply[F[_]](d: io.iohk.scevm.storage.execution.SlotBasedMempool[F, SignedTransaction]): SlotBasedMempool[F] =
      new SlotBasedMempool[F] {
        override def getAliveElements(slot: Slot): F[List[SignedTransaction]] = d.getAliveElements(slot)
      }
  }

  def passive[F[_]: Functor](
      mempool: SlotBasedMempool[F]
  ): NextBlockTransactions[F] =
    new PassiveNewBlockTransactionProvider[F](mempool)

  def validator[F[_]: Sync: Trace, SignatureScheme <: AbstractSignatureScheme](
      blockchainConfig: BlockchainConfig,
      mempool: SlotBasedMempool[F],
      worldStateBuilder: WorldStateBuilder[F],
      signTransactionProvider: HandoverTransactionProvider[F, SignatureScheme],
      incomingTransactionService: IncomingTransactionsService[F],
      metrics: SidechainMetrics[F],
      epochDerivation: SidechainEpochDerivation[F]
  ): NextBlockTransactions[F] =
    new ValidatorNewBlockTransactionsProvider[F, SignatureScheme](
      mempool,
      worldStateBuilder,
      signTransactionProvider,
      metrics,
      incomingTransactionService,
      epochDerivation,
      TransactionFilter
        .getPendingTransactions(blockchainConfig.chainId, blockchainConfig.genesisData.accountStartNonce)
    )
}
