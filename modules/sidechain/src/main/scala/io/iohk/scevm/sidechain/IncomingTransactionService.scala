package io.iohk.scevm.sidechain

import cats.effect.kernel.Sync
import cats.syntax.all._
import io.iohk.scevm.domain.Slot
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

trait IncomingTransactionsService[F[_]] {
  def getTransactions(world: WorldType): F[List[SystemTransaction]]
}

object IncomingTransactionsService {

  trait BridgeContract[F[_]] {
    def createUnlockTransaction(
        mTx: ValidIncomingCrossChainTransaction,
        currentSlot: Slot
    ): F[SystemTransaction]
  }
}

class IncomingTransactionsServiceImpl[F[_]: Sync](
    newIncomingTransactionsProvider: IncomingCrossChainTransactionsProvider[F],
    bridgeContract: IncomingTransactionsService.BridgeContract[EvmCall[F, *]]
) extends IncomingTransactionsService[F] {
  implicit private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  override def getTransactions(
      world: WorldType
  ): F[List[SystemTransaction]] =
    for {
      validMainchainTxs <- newIncomingTransactionsProvider.getTransactions(world)
      _                 <- Logger[F].debug(show"Found ${validMainchainTxs.length} new incoming transactions")
      _                 <- Logger[F].trace(show"New incoming transactions: $validMainchainTxs")
      transactions      <- buildTransactions(validMainchainTxs, world)
    } yield transactions

  private def buildTransactions(
      validMainchainTxs: List[ValidIncomingCrossChainTransaction],
      world: WorldType
  ): F[List[SystemTransaction]] =
    validMainchainTxs
      .traverse { case mTx =>
        bridgeContract
          .createUnlockTransaction(mTx, world.blockContext.slotNumber)
          .run(world)
      }
}
