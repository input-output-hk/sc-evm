package io.iohk.scevm.consensus.domain

import cats.MonadThrow
import cats.data.{EitherT, OptionT}
import cats.syntax.all._
import io.iohk.scevm.consensus.validators.ChainingValidator
import io.iohk.scevm.db.storage.BranchProvider.BranchRetrievalError
import io.iohk.scevm.db.storage.{BlocksReader, BranchProvider}
import io.iohk.scevm.domain.ObftHeader

import BranchService._

trait BranchService[F[_]] {
  def getBranchWithoutValidation(from: ObftHeader, to: ObftHeader): F[Either[BranchService.BranchError, HeadersBranch]]
  def getBranchAndValidate(from: ObftHeader, to: ObftHeader): F[Either[BranchService.BranchError, HeadersBranch]]
  def withSuffixes(branch: HeadersBranch): F[Seq[HeadersBranch]]
  def toBlocksBranch(headersBranch: HeadersBranch): F[Option[BlocksBranch]]
}

class BranchServiceImpl[F[_]: MonadThrow](blocksReader: BlocksReader[F], branchProvider: BranchProvider[F])(
    chainingValidator: ChainingValidator
) extends BranchService[F] {

  /** Get the blocks between from and to, but without apply chain validation on it
    * @param from the tip of the branch
    * @param to the ancestor of the branch
    * @return the branch, a missing parent or an error
    */
  override def getBranchWithoutValidation(
      from: ObftHeader,
      to: ObftHeader
  ): F[Either[BranchService.BranchError, HeadersBranch]] =
    fetchAsBranch(branchProvider)(from, to).value

  /** Get the blocks between from and to, and apply chain validation on it
    * @param from the tip of the branch
    * @param to the ancestor of the branch
    * @return the branch, a missing parent or an error
    */
  override def getBranchAndValidate(
      from: ObftHeader,
      to: ObftHeader
  ): F[Either[BranchService.BranchError, HeadersBranch]] =
    fetchAsBranchAndValidate(branchProvider)(from, to).value

  /** Extends the branch with all its children
    * @param branch the branch from stable to the new block
    * @return a list of branch from stable to the tip of each children branch of the new block
    */
  override def withSuffixes(branch: HeadersBranch): F[Seq[HeadersBranch]] =
    branchProvider.findSuffixesTips(branch.tip.hash).flatMap {
      case Nil => Seq(branch).pure[F]
      case suffixesTip =>
        suffixesTip.traverse { tip =>
          fetchAsBranchAndValidate(branchProvider)(tip, branch.ancestor)
            .getOrElseF(DisconnectedBranchFailure(tip, branch.ancestor).raiseError[F, HeadersBranch])
        }
    }

  override def toBlocksBranch(headersBranch: HeadersBranch): F[Option[BlocksBranch]] =
    (for {
      ancestor <- OptionT(blocksReader.getBlock(headersBranch.ancestor.hash))
      belly    <- OptionT(headersBranch.belly.traverse(block => blocksReader.getBlock(block.hash)).map(_.sequence))
      tip      <- OptionT(blocksReader.getBlock(headersBranch.tip.hash))
    } yield BlocksBranch(ancestor, belly, tip)).value

  private def fetchAsBranchAndValidate(
      branchProvider: BranchProvider[F]
  )(from: ObftHeader, to: ObftHeader): EitherT[F, BranchService.BranchError, HeadersBranch] =
    for {
      branch <- fetchAsBranch(branchProvider)(from, to)
      _      <- EitherT.fromEither(validate(branch))
    } yield branch

  private def fetchAsBranch(
      branchProvider: BranchProvider[F]
  )(from: ObftHeader, to: ObftHeader): EitherT[F, BranchService.BranchError, HeadersBranch] =
    for {
      chain <- EitherT(branchProvider.fetchChain(from, to)).leftMap(BranchService.RetrievalError)
      branch <- EitherT.liftF(chain match {
                  case ancestor +: belly :+ tip => HeadersBranch(ancestor, belly.toVector, tip).pure
                  case _                        => BranchFormatFailure(from, to).raiseError[F, HeadersBranch]
                })
    } yield branch

  private def validate(branch: HeadersBranch): Either[BranchService.BranchError, ObftHeader] =
    branch.unexecuted
      .foldLeftM(branch.ancestor) { case (parent, header) =>
        chainingValidator.validate(parent, header)
      }
      .leftMap(BranchService.ChainingValidationError)
}

object BranchService {

  sealed trait BranchError
  final case class ChainingValidationError(error: ChainingValidator.ChainingError) extends BranchError
  final case class RetrievalError(error: BranchRetrievalError)                     extends BranchError

  final case class BranchFormatFailure(from: ObftHeader, to: ObftHeader)
      extends Exception(show"fetching from=$from, to=$to did not return enough blocks to be a branch")

  final case class DisconnectedBranchFailure(from: ObftHeader, to: ObftHeader)
      extends Exception(show"fetching the supposedly connected branch from=$from, to=$to failed")
}
