package io.iohk.scevm.sidechain

import cats.Applicative
import cats.effect.kernel.Sync
import cats.syntax.all._
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.IncomingTransactionId
import io.iohk.scevm.sidechain.IncomingCrossChainTransactionsProviderImpl._
import io.iohk.scevm.sidechain.PendingTransactionsService.PendingTransactionsResponse
import io.iohk.scevm.sidechain.{IncomingCrossChainTransaction, ValidIncomingCrossChainTransaction}
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainSlot, MainchainTxHash}
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

trait IncomingCrossChainTransactionsProvider[F[_]] {

  /** Gets recent cross-chain transactions
    *
    * @param slot  restricts returned transactions to be stable in mainchain at this sidechain slot
    * @param world state for currentBlock, contains information about already processed transactions
    */
  def getTransactions(
      world: WorldType
  ): F[List[ValidIncomingCrossChainTransaction]]

}

object IncomingCrossChainTransactionsProvider {
  def dummy[F[_]: Applicative]: IncomingCrossChainTransactionsProvider[F] =
    _ => List.empty[ValidIncomingCrossChainTransaction].pure
}

trait PendingIncomingCrossChainTransactionProvider[F[_]] {

  /** Gets recent stable and unstable cross-chain transactions
    * @param world state for currentBlock, contains information about already processed transactions
    */
  def getPendingTransactions(
      world: WorldType
  ): F[PendingTransactionsResponse]
}

object PendingIncomingCrossChainTransactionProvider {

  def dummy[F[_]: Applicative]: PendingIncomingCrossChainTransactionProvider[F] =
    _ => PendingTransactionsResponse(List.empty, List.empty).pure
}

object IncomingCrossChainTransactionsProviderImpl {

  trait MainchainTransactionProvider[F[_]] {
    def getStableIncomingTransactions(
        after: Option[MainchainTxHash],
        at: MainchainSlot
    ): F[List[IncomingCrossChainTransaction]]
  }

  trait MainchainUnstableTransactionProvider[F[_]] {
    def getUnstableIncomingTransactions(
        after: Option[MainchainTxHash],
        at: MainchainSlot
    ): F[List[IncomingCrossChainTransaction]]
  }

  trait BridgeContract[F[_]] {
    def getLastProcessedIncomingTransaction: F[Option[IncomingTransactionId]]
  }
}

class IncomingCrossChainTransactionsProviderImpl[F[_]: Sync: Trace](
    bridgeContract: BridgeContract[EvmCall[F, *]],
    mainchainTransactions: MainchainTransactionProvider[F],
    unstableMainchainTransactions: MainchainUnstableTransactionProvider[F],
    mainchainSlotDerivation: MainchainSlotDerivation
) extends IncomingCrossChainTransactionsProvider[F]
    with PendingIncomingCrossChainTransactionProvider[F] {
  implicit private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  override def getTransactions(
      world: WorldType
  ): F[List[ValidIncomingCrossChainTransaction]] =
    for {
      lastProcessedUtxo <- getLastProcessedUtxo(world)
      validMainchainTxs <- getStableIncomingTransactions(lastProcessedUtxo, world.blockContext.slotNumber)
    } yield validMainchainTxs

  override def getPendingTransactions(
      world: WorldType
  ): F[PendingTransactionsResponse] = for {
    lastProcessedUtxo  <- getLastProcessedUtxo(world)
    stableMainchainTxs <- getStableIncomingTransactions(lastProcessedUtxo, world.blockContext.slotNumber)
    unstableMainchainTxs <- getUnstableIncomingTransactions(
                              stableMainchainTxs.lastOption.map(_.txId).orElse(lastProcessedUtxo),
                              world.blockContext.slotNumber
                            )
  } yield PendingTransactionsResponse(stable = stableMainchainTxs, unstable = unstableMainchainTxs)

  private def getLastProcessedUtxo(world: WorldType): F[Option[MainchainTxHash]] =
    for {
      maybeLastTx <- Trace[F].span("BridgeContract::getLastProcessedIncomingTransaction")(
                       bridgeContract.getLastProcessedIncomingTransaction(world)
                     )
      lastProcessedUtxo <- maybeLastTx.traverse { incomingTxId =>
                             Sync[F].fromEither(incomingTxId.toTxHash.left.map(msg => new RuntimeException(msg)))
                           }
    } yield lastProcessedUtxo

  private def getStableIncomingTransactions(
      lastProcessedUtxo: Option[MainchainTxHash],
      slot: Slot
  ): F[List[ValidIncomingCrossChainTransaction]] = {
    val mainchainSlot = mainchainSlotDerivation.getMainchainSlot(slot)
    for {
      _ <- Logger[F].debug(
             show"Getting stable mainchain transactions after $lastProcessedUtxo, at mainchain slot: $mainchainSlot"
           )
      mainchainTxs <- Trace[F].span("MainchainTransactionProvider::getStableIncomingTransactions")(
                        mainchainTransactions.getStableIncomingTransactions(lastProcessedUtxo, mainchainSlot)
                      )
    } yield mainchainTxs.map(toValidTransaction)
  }

  private def getUnstableIncomingTransactions(
      after: Option[MainchainTxHash],
      to: Slot
  ): F[List[ValidIncomingCrossChainTransaction]] = {
    val mainchainSlot = mainchainSlotDerivation.getMainchainSlot(to)
    for {
      _ <- Logger[F].debug(
             show"Getting unstable mainchain transactions after $after, up to mainchain slot: $mainchainSlot"
           )
      mainchainTxs <- Trace[F].span("MainchainUnstableTransactionProvider::getUnstableIncomingTransactions")(
                        unstableMainchainTransactions.getUnstableIncomingTransactions(after, mainchainSlot)
                      )
    } yield mainchainTxs.map(toValidTransaction)
  }

  private def toValidTransaction(t: IncomingCrossChainTransaction): ValidIncomingCrossChainTransaction =
    ValidIncomingCrossChainTransaction(t.recipientDatum.sidechainAddress, t.value, t.txId, t.stableAtBlock)
}
