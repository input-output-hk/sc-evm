package io.iohk.scevm.db.storage

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.scevm.db.storage.BranchProvider.BranchRetrievalError
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftHeader}

trait BranchProvider[F[_]] {

  /** Return the list of direct children of the given block */
  def getChildren(parent: BlockHash): F[Seq[ObftHeader]]

  /** Return the tips of children branches (leaves) */
  def findSuffixesTips(parent: BlockHash): F[Seq[ObftHeader]]

  /** Fetch the connected sequence of headers between the two parameters included */
  def fetchChain(from: ObftHeader, to: ObftHeader): F[Either[BranchRetrievalError, List[ObftHeader]]]
}

object BranchProvider {
  def apply[F[_]](implicit ev: BranchProvider[F]): BranchProvider[F] = ev

  sealed trait BranchRetrievalError
  final case class MissingParent(
      missingBlockHash: BlockHash,
      missingBlockNumber: BlockNumber,
      orphanBlockHeader: ObftHeader
  ) extends BranchRetrievalError {
    override def toString: String = Show[MissingParent].show(this)
  }

  object MissingParent {
    implicit val show: Show[MissingParent] = cats.derived.semiauto.show
  }

  object BranchRetrievalError {
    implicit val show: Show[BranchRetrievalError] = cats.derived.semiauto.show
  }

  /** There is no way to connect two blocks (one is not the ancestor of the other) */
  final case object NotConnected extends BranchRetrievalError

  def userFriendlyMessage(error: BranchRetrievalError): String = error match {
    case MissingParent(missingBlockHash, _, orphanBlockHeader) =>
      show"Impossible to retrieve the branch because block $missingBlockHash (parent of ${orphanBlockHeader.idTag}) is missing from the storage."
    case NotConnected =>
      "The requested branch is invalid because the first block of the branch is not an ancestor of the last block."

  }
}
