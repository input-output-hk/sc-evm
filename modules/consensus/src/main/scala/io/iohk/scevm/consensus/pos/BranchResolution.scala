package io.iohk.scevm.consensus.pos

import cats.MonadThrow
import cats.data.EitherT
import cats.syntax.all._
import io.iohk.scevm.consensus.domain.BranchService.BranchError
import io.iohk.scevm.consensus.domain.{BranchService, HeadersBranch}
import io.iohk.scevm.domain.ObftHeader

/** Gathers the logic of keeping the alternative blockchain updatedF */
trait BranchResolution[F[_]] {
  def getCandidateBranches(
      bestBlock: ObftHeader,
      stableBlock: ObftHeader,
      incomingBlock: ObftHeader
  ): F[Either[BranchError, Seq[HeadersBranch]]]
}

class BranchResolutionImpl[F[_]: MonadThrow](
    branchService: BranchService[F],
    headerOrdering: Ordering[ObftHeader]
) extends BranchResolution[F] {

  /** Returns branches impacted by the incoming block that are possible best candidates
    * @param incomingBlock the new block
    * @return the connected branches from stable to the tips of the children of the new block
    * (all suffix candidates must be returned because selection can only happen after validation and execution)
    */
  def getCandidateBranches(
      bestBlock: ObftHeader,
      stableBlock: ObftHeader,
      incomingBlock: ObftHeader
  ): F[Either[BranchError, Seq[HeadersBranch]]] =
    if (stableBlock.number >= incomingBlock.number) Seq.empty[HeadersBranch].asRight[BranchError].pure
    else getCandidateBranchesImpl(bestBlock, stableBlock, incomingBlock)

  private def getCandidateBranchesImpl(
      bestBlock: ObftHeader,
      stableBlock: ObftHeader,
      incomingBlock: ObftHeader
  ): F[Either[BranchError, Seq[HeadersBranch]]] = {
    val extending = bestBlock.isParentOf(incomingBlock)
    (for {
      branchToStable <- EitherT(getNewBlockBranch(stableBlock, incomingBlock))
      branchWithSuffixes <-
        EitherT.liftF[F, BranchError, Seq[HeadersBranch]](extendWithSuffixes(branchToStable))
    } yield
      if (extending) {
        branchWithSuffixes
      } else {
        branchWithSuffixes.filter(isBetterBranch(bestBlock))
      }).value
  }

  private def getNewBlockBranch(
      stableBlock: ObftHeader,
      newBlock: ObftHeader
  ): F[Either[BranchError, HeadersBranch]] =
    branchService.getBranchAndValidate(newBlock, stableBlock)

  private def extendWithSuffixes(branch: HeadersBranch): F[Seq[HeadersBranch]] =
    branchService.withSuffixes(branch)

  private def isBetterBranch(best: ObftHeader)(branch: HeadersBranch): Boolean =
    headerOrdering.compare(branch.tip, best) > 0

}

object BranchResolution {
  def apply[F[_]](implicit ev: BranchResolution[F]): BranchResolution[F] = ev
}
