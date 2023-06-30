package io.iohk.scevm.consensus.pos

import cats.data.{EitherT, NonEmptyList}
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import cats.{Applicative, MonadThrow, Show}
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.domain.BlocksBranch
import io.iohk.scevm.consensus.pos.BranchExecution.RangeExecutionError.{BlockError, FetchError}
import io.iohk.scevm.consensus.pos.ObftBlockExecution.{BlockExecutionError, BlockExecutionResult}
import io.iohk.scevm.db.storage.BranchProvider.BranchRetrievalError
import io.iohk.scevm.db.storage.{BlocksReader, BranchProvider, ReceiptStorage}
import io.iohk.scevm.domain.{BlockContext, BlockHash, ObftBlock, ObftHeader}
import io.iohk.scevm.exec.vm.InMemoryWorldState
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import BranchExecution.RangeExecutionError.NoHeaders
import BranchExecution._

trait BranchExecution[F[_]] {

  /** Execute a branch
    * @return the tip of the branch if valid
    */
  def execute(branch: BlocksBranch): F[Either[BlockExecutionError, BlocksBranch]]

  /** Execute a range of blocks. The blocks must exist in the storage.
    * 'from' is an ancestor of 'to'
    */
  def executeRange(from: ObftHeader, to: ObftHeader): F[Either[RangeExecutionError, Unit]]
}

class BranchExecutionImpl[F[_]: LoggerFactory: MonadCancelThrow](
    blockExecution: ObftBlockExecution[F],
    blocksReader: BlocksReader[F],
    branchProvider: BranchProvider[F],
    receiptStorage: ReceiptStorage[F],
    worldStateBuilder: WorldStateBuilder[F]
) extends BranchExecution[F] {

  private val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def execute(branch: BlocksBranch): F[Either[BlockExecutionError, BlocksBranch]] = {
    val firstUnexecutedHeader = branch.unexecuted.headOption.getOrElse(branch.ancestor).header
    worldStateBuilder
      .getWorldStateForBlock(
        branch.ancestor.header.stateRoot,
        BlockContext.from(firstUnexecutedHeader)
      )
      .use { state =>
        val initValue = BlockAndState(branch.ancestor, state)

        branch.unexecuted
          .to(LazyList)
          .foldLeftM(initValue) { case (prev, block) =>
            executeBlock(prev, block).map(state => BlockAndState(block, state))
          }
          .map(_ => branch)
          .redeemWith(
            recover = {
              case error: BlockExecutionError => error.asLeft[BlocksBranch].pure[F]
              case error                      => error.raiseError[F, Either[BlockExecutionError, BlocksBranch]]
            },
            bind = value => value.asRight[BlockExecutionError].pure[F]
          )
      }
  }

  override def executeRange(from: ObftHeader, to: ObftHeader): F[Either[RangeExecutionError, Unit]] =
    if (from.hash == to.hash) Applicative[F].pure(Right(()))
    else
      (for {
        _ <- EitherT.liftF(logger.trace(s"Fetching headers from storage, from=${from.idTag}, to=${to.idTag}"))
        headers <-
          EitherT
            .apply(branchProvider.fetchChain(to, from)) // branchProvider expects `to` < `from`
            .leftSemiflatTap(error =>
              logger.error(
                s"Couldn't fetch chain of headers (${from.idTag} to ${to.idTag}) from the storage: " +
                  s"${BranchProvider.userFriendlyMessage(error)}."
              )
            )
            .leftMap(FetchError)
        nonEmptyHeaders <-
          EitherT
            .fromOption(NonEmptyList.fromList(headers), NoHeaders())
            .leftSemiflatTap(_ =>
              logger.error(
                s"Couldn't fetch chain of headers (${from.idTag} to ${to.idTag}) from the storage: the returned list of header was empty."
              )
            )
        _ <- EitherT.liftF(logger.trace(s"Fetched ${headers.size} headers, about to execute blocks"))
        _ <- EitherT(executeBlocks(nonEmptyHeaders.head.hash, nonEmptyHeaders.tail.map(_.hash)))
               .leftMap[RangeExecutionError](BlockError)
      } yield ()).value

  private def executeBlocks(
      validatedAncestor: BlockHash,
      unvalidatedBlocks: List[BlockHash]
  ) =
    getBlockOrFail(validatedAncestor)
      .flatMap { ancestorBlock =>
        worldStateBuilder
          .getWorldStateForBlock(
            ancestorBlock.header.stateRoot,
            // we take the block context from the ancestor but it's expected to be updated in `executeBlock`
            BlockContext.from(ancestorBlock.header)
          )
          .use { ancestorState =>
            val initValue = BlockAndState(ancestorBlock, ancestorState)
            unvalidatedBlocks.map(getBlockOrFail).foldLeftM(initValue) { case (prev, blockF) =>
              for {
                block <- blockF
                state <- executeBlock(prev, block)
                _     <- logger.info(show"Successfully executed $block.").whenA(block.number.value % 250 == 0)
              } yield BlockAndState(block, state)
            }
          }
      }
      .attemptNarrow[BlockExecutionError]

  private def getBlockOrFail(hash: BlockHash): F[ObftBlock] =
    for {
      maybeBlock <- blocksReader.getBlock(hash)
      block      <- MonadThrow[F].fromOption(maybeBlock, MissingBlock(hash))
    } yield block

  private def executeBlock(
      prev: BlockAndState,
      block: ObftBlock
  ): F[InMemoryWorldState] =
    executeBlockAndSaveReceipts(block, prev.state.modifyBlockContext(_ => BlockContext.from(block.header)))
      .foldF(MonadThrow[F].raiseError, _.worldState.pure[F])

  private def executeBlockAndSaveReceipts(
      block: ObftBlock,
      prevState: InMemoryWorldState
  ): EitherT[F, BlockExecutionError, BlockExecutionResult] =
    for {
      result <- EitherT(blockExecution.executeBlock(block, prevState))
      _      <- EitherT.liftF(receiptStorage.putReceipts(block.hash, result.receipts))
    } yield result

}

object BranchExecution {
  def apply[F[_]](implicit ev: BranchExecution[F]): BranchExecution[F] = ev

  final case class BlockAndState(block: ObftBlock, state: InMemoryWorldState)

  final case class MissingBlock(hash: BlockHash) extends Exception(show"missing block $hash")

  sealed trait RangeExecutionError
  object RangeExecutionError {
    final case class BlockError(error: BlockExecutionError) extends RangeExecutionError

    final case class FetchError(error: BranchRetrievalError) extends RangeExecutionError

    final case class NoHeaders() extends RangeExecutionError

    implicit val show: Show[RangeExecutionError] = cats.derived.semiauto.show
  }

}
