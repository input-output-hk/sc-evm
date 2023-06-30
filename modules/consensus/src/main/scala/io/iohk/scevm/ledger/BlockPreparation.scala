package io.iohk.scevm.ledger

import cats.FlatMap
import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.BlockPreExecution
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.{BlockContext, ObftHeader, Receipt, SignedTransaction}
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.utils.Logger

import BlockRewarder.BlockRewarder
import TransactionExecution.{StateBeforeFailure, TxsExecutionError}

trait BlockPreparation[F[_]] {
  def prepareBlock(
      blockContext: BlockContext,
      transactionList: Seq[SignedTransaction],
      parent: ObftHeader
  ): F[PreparedBlock]
}

class BlockPreparationImpl[F[_]: MonadCancelThrow](
    blockPreExecution: BlockPreExecution[F],
    transactionExecution: TransactionExecution[F],
    payBlockReward: BlockRewarder[F],
    worldStateBuilder: WorldStateBuilder[F]
) extends BlockPreparation[F]
    with Logger {

  def prepareBlock(
      blockContext: BlockContext,
      transactionList: Seq[SignedTransaction],
      parent: ObftHeader
  ): F[PreparedBlock] = {
    val initialWorldR = worldStateBuilder.getWorldStateForBlock(parent.stateRoot, blockContext)

    initialWorldR.use { initialWorld =>
      for {
        state <- blockPreExecution.prepare(initialWorld)
        (execResult @ BlockExecutionResult(resultingWorldState, _, _), txExecuted) <-
          executePreparedTransactions(transactionList, state)
        worldToPersist <- payBlockReward(resultingWorldState)
        worldPersisted  = InMemoryWorldState.persistState(worldToPersist)
      } yield PreparedBlock(
        txExecuted,
        execResult,
        worldPersisted.stateRootHash,
        worldPersisted
      )
    }
  }

  final private def executePreparedTransactions(
      signedTransactions: Seq[SignedTransaction],
      world: InMemoryWorldState,
      accumGas: BigInt = 0,
      accumReceipts: Seq[Receipt] = Nil,
      executed: Seq[SignedTransaction] = Nil
  ): F[(BlockExecutionResult, Seq[SignedTransaction])] =
    FlatMap[F].tailRecM((signedTransactions, world, accumGas, accumReceipts, executed)) {
      case (signedTransactions, world, accumGas, accumReceipts, executed) =>
        val result =
          transactionExecution.executeTransactions(signedTransactions, world, accumGas, accumReceipts)
        result.map {
          case Left(TxsExecutionError(stx, StateBeforeFailure(worldState, gas, receipts), reason)) =>
            log.info(
              show"Transaction ${stx.hash} ignored while preparing block at ${world.blockContext.slotNumber}, ${world.blockContext.number}. Reason: $reason"
            )
            val txIndex = signedTransactions.indexWhere(tx => tx.hash == stx.hash)
            Left(
              (
                signedTransactions.drop(txIndex + 1),
                worldState,
                gas,
                receipts,
                executed ++ signedTransactions.take(txIndex)
              )
            )
          case Right(blockResult) => Right((blockResult, executed ++ signedTransactions))
        }
    }

}
