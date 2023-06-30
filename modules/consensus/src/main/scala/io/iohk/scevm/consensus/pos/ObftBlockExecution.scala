package io.iohk.scevm.consensus.pos

import cats.data.EitherT
import cats.syntax.all._
import cats.{MonadThrow, Show}
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.domain.{BlockHash, ObftBlock, Receipt}
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.BlockRewarder.BlockRewarder
import io.iohk.scevm.ledger.TransactionExecution
import io.iohk.scevm.ledger.TransactionExecution.TxsExecutionError
import io.iohk.scevm.metrics.instruments.Counter

import ObftBlockExecution.BlockExecutionError.ErrorDuringExecution
import ObftBlockExecution.{BlockExecutionError, BlockExecutionResult}

trait ObftBlockExecution[F[_]] {
  def executeBlock(
      block: ObftBlock,
      state: InMemoryWorldState
  ): F[Either[BlockExecutionError, BlockExecutionResult]]
}

class ObftBlockExecutionImpl[F[_]: MonadThrow](
    blockPreExecution: BlockPreExecution[F],
    transactionExecution: TransactionExecution[F],
    postExecutionValidator: PostExecutionValidator[F],
    payBlockReward: BlockRewarder[F],
    executedBlocksCounter: Counter[F]
) extends ObftBlockExecution[F] {
  import ObftBlockExecution._

  def executeBlock(
      block: ObftBlock,
      initialState: InMemoryWorldState
  ): F[Either[BlockExecutionError, BlockExecutionResult]] =
    (for {
      state      <- EitherT.right(blockPreExecution.prepare(initialState))
      execResult <- executeTransactionAndPayReward(state, block)
      _ <- EitherT(postExecutionValidator.validate(state, block.header, execResult))
             .leftMap(BlockExecutionError.afterExec)
    } yield execResult).value
      .adaptErr { e =>
        new RuntimeException(show"Error while executing block $block", e)
      }

  private[pos] def executeTransactionAndPayReward(
      state: InMemoryWorldState,
      block: ObftBlock
  ): EitherT[F, BlockExecutionError, BlockExecutionResult] =
    for {
      transactionsResult <-
        EitherT(
          transactionExecution.executeTransactions(block.body.transactionList, state)
        )
          .leftMap(ErrorDuringExecution(block.hash, _))
      worldWithReward <- EitherT.liftF(
                           payBlockReward(
                             transactionsResult.worldState
                           )
                         )
      _        <- EitherT.liftF(executedBlocksCounter.inc)
      persisted = InMemoryWorldState.persistState(worldWithReward)
    } yield transactionsResult.copy(worldState = persisted)
}

object ObftBlockExecution {

  final case class BlockExecutionResult(
      worldState: InMemoryWorldState,
      gasUsed: BigInt = 0,
      receipts: Seq[Receipt] = Nil
  )

  sealed abstract class BlockExecutionError(val message: String)
      extends Exception(message)
      with Product
      with Serializable {
    def hash: BlockHash
  }

  object BlockExecutionError {
    final case class ErrorDuringExecution(hash: BlockHash, transactionError: TxsExecutionError)
        extends BlockExecutionError(show"Error during execution of block $hash: $transactionError")

    object ErrorDuringExecution {
      implicit val show: Show[ErrorDuringExecution] = cats.derived.semiauto.show
    }

    final case class ValidationAfterExecError(error: PostExecutionError)
        extends BlockExecutionError(show"Error after execution of block ${error.hash}: $error") {
      override def hash: BlockHash = error.hash
    }
    object ValidationAfterExecError {
      implicit val show: Show[ValidationAfterExecError] =
        Show.show(error => s"ValidationAfterExecError(error=${error.error})")
    }

    def afterExec(error: PostExecutionError): BlockExecutionError = ValidationAfterExecError(error)
  }
}
