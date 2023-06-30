package io.iohk.scevm.consensus.pos

import cats.data.OptionT
import cats.effect.MonadCancelThrow
import cats.effect.std.Semaphore
import cats.syntax.all._
import cats.{Applicative, Monad, Parallel, Show}
import io.iohk.scevm.consensus.domain.BranchService.{ChainingValidationError, RetrievalError}
import io.iohk.scevm.consensus.domain.{BlocksBranch, BranchService, HeadersBranch}
import io.iohk.scevm.consensus.metrics.ConsensusMetrics
import io.iohk.scevm.db.storage.BranchProvider.{MissingParent, NotConnected}
import io.iohk.scevm.db.storage.ReceiptStorage
import io.iohk.scevm.domain.{BlocksDistance, ObftBlock, ObftHeader}
import io.iohk.scevm.ledger.BlockImportService.ImportedBlock
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import ConsensusService._

trait ConsensusService[F[_]] {
  def resolve(importedBlock: ImportedBlock, currentBranch: CurrentBranch): F[ConsensusResult]
}

class ConsensusServiceImpl[F[_]: MonadCancelThrow: Parallel: LoggerFactory](
    branchService: BranchService[F],
    branchResolution: BranchResolution[F],
    branchExecution: BranchExecution[F],
    receiptStorage: ReceiptStorage[F],
    semaphore: Semaphore[F],
    branchUpdateListener: BranchUpdateListener[F],
    consensusMetrics: ConsensusMetrics[F]
)(
    stabilityParameter: Int
) extends ConsensusService[F] {
  private val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def resolve(importedBlock: ImportedBlock, currentBranch: CurrentBranch): F[ConsensusResult] =
    semaphore.permit
      .use { _ =>
        val CurrentBranch(stable, best) = currentBranch
        for {
          _ <-
            logger.debug(show"Evaluating block ${importedBlock.idTag}, (current best=$best, current stable=$stable )")
          candidates <- if (importedBlock.fullyValidated) {
                          branchService.getBranchWithoutValidation(importedBlock.header, stable).map(_.map(Seq(_)))
                        } else {
                          branchResolution.getCandidateBranches(best, stable, importedBlock.header)
                        }
          consensusResult <- candidates.fold[F[ConsensusResult]](
                               {
                                 case RetrievalError(missing: MissingParent) =>
                                   (DisconnectedBranchMissingParent(importedBlock.block, missing): ConsensusResult).pure
                                 case RetrievalError(NotConnected) =>
                                   logger
                                     .warn(
                                       show"Detected block ${importedBlock.idTag} on branch forked beyond stable=$stable"
                                     )
                                     .as(ForkedBeyondStableBranch(importedBlock.block))
                                 case ChainingValidationError(error) =>
                                   logger
                                     .info(s"Detected invalid branch: $error")
                                     .as(ConnectedBranch(importedBlock.block, None))
                               },
                               branches => executeBranches(importedBlock.block, branches)
                             )
          _ <-
            logger.debug(
              show"Consensus with result $consensusResult on block=${importedBlock.idTag}, best=$best, stable=$stable"
            )
          _ <- handleConsensusResult(consensusResult)
        } yield consensusResult
      }

  private def handleConsensusResult(consensusResult: ConsensusResult): F[Unit] =
    consensusResult match {
      case ConsensusService.ConnectedBranch(_, Some(better)) =>
        branchUpdateListener.newBranch(better)
      case ConsensusService.ForkedBeyondStableBranch(block) =>
        logger.warn(
          show"Received block ${block.header.idTag} which belongs to a branch forked before the stable block"
        ) >>
          logger.debug(show"Full block from forked branch: $block")
      case ConsensusService.DisconnectedBranchMissingParent(block, missingParent) =>
        logger.warn(show"Received block ${block.header.idTag} with missing parent $missingParent.") >>
          logger.debug(show"Full block with missing parent: $block")
      case _ => Applicative[F].unit
    }

  private def executeBranches(
      block: ObftBlock,
      candidates: Seq[HeadersBranch]
  ): F[ConsensusResult] =
    candidates.parTraverse(execute).map(executedBranches => selectBestAndStable(block, executedBranches.flatten))

  private def execute(candidate: HeadersBranch): F[Option[BetterBranch]] =
    (for {
      blocksBranch    <- OptionT(branchService.toBlocksBranch(candidate))
      reducedBranch   <- OptionT.liftF(reduceBranch(blocksBranch, receiptStorage))
      betterBranchOpt <- OptionT(executeBranch(reducedBranch, blocksBranch))
    } yield betterBranchOpt).value

  private def executeBranch(reducedBranch: BlocksBranch, originalBranch: BlocksBranch) =
    branchExecution
      .execute(reducedBranch)
      .attempt
      .flatMap {
        case Left(technicalError) =>
          consensusMetrics.branchDiscardedRuntimeError.inc *>
            logger
              .error(technicalError)(s"Branch discarded due to technical error")
              .as(none[BetterBranch])
        case Right(Left(executionError)) =>
          logger
            .info(s"Branch discarded due to $executionError")
            .as(none[BetterBranch])

        case Right(Right(_)) =>
          val branch = toBetterBranch(originalBranch)
          logger
            .debug(show"Branch validated tip=${branch.newBest}")
            .as(branch.some)
      }

  private def selectBestAndStable(block: ObftBlock, branches: Seq[BetterBranch]): ConsensusResult = {
    val bestBranch = branches.headOption
    ConnectedBranch(block, bestBranch)
  }

  private def toBetterBranch(branch: BlocksBranch): BetterBranch = {
    def isStable(block: ObftBlock): Boolean =
      branch.tip.number - BlocksDistance(stabilityParameter) >= block.number
    val stablePart = branch.unexecuted.filter(isStable)
    BetterBranch(stablePart, branch.tip.header)
  }
}
//scalastyle:on

object ConsensusService {
  def apply[F[_]](implicit ev: ConsensusService[F]): ConsensusService[F] = ev

  final case class BetterBranch(stablePart: Vector[ObftBlock], newBest: ObftHeader) {
    val newStable: Option[ObftHeader] = stablePart.lastOption.map(_.header)

    override def toString: String =
      BetterBranch.show.show(this)
  }

  object BetterBranch {
    implicit val show: Show[BetterBranch] =
      Show.show(b => show"BetterBranch(newBest=${b.newBest}, newStable=${b.stablePart.lastOption})")
  }

  trait BranchUpdateListener[F[_]] {
    def newBranch(betterBranch: BetterBranch): F[Unit]
  }

  sealed trait ConsensusResult extends Product with Serializable {
    val block: ObftBlock
  }
  object ConsensusResult {
    implicit val show: Show[ConsensusResult] = cats.derived.semiauto.show
  }
  final case class ConnectedBranch(block: ObftBlock, maybeBetter: Option[BetterBranch]) extends ConsensusResult {
    override def toString: String = show"ConnectedBranch(block=$block, maybeBetter=$maybeBetter)"
  }

  final case class DisconnectedBranchMissingParent(block: ObftBlock, missingParent: MissingParent)
      extends ConsensusResult

  final case class ForkedBeyondStableBranch(block: ObftBlock) extends ConsensusResult

  /** Removes potential fully-executed blocks prefix
    * @param branch the branch
    * @return a new branch with one fully-executed ancestor followed by unvalidated blocks
    */
  def reduceBranch[F[_]: Monad](branch: BlocksBranch, storage: ReceiptStorage[F]): F[BlocksBranch] = {
    final case class FoldState(
        ancestor: ObftBlock,
        blocksToExecute: Vector[ObftBlock],
        foundExecutedBlock: Boolean
    )

    branch.belly.reverse
      .foldM(FoldState(branch.ancestor, Vector.empty, foundExecutedBlock = false)) {
        case (FoldState(_, blocksToExecute, false), block) =>
          storage
            .getReceiptsByHash(block.hash)
            .map { receipts =>
              if (receipts.isEmpty)
                FoldState(block, blocksToExecute :+ block, foundExecutedBlock = false)
              else
                FoldState(block, blocksToExecute, foundExecutedBlock = true)
            }
        case (state @ FoldState(_, _, true), _) => state.pure[F]
      }
      .map { case FoldState(ancestor, blocksToExecute, foundExecutedBlock) =>
        if (foundExecutedBlock)
          BlocksBranch(ancestor, blocksToExecute.reverse, branch.tip)
        else
          BlocksBranch(branch.ancestor, blocksToExecute.reverse, branch.tip)
      }
  }
}
