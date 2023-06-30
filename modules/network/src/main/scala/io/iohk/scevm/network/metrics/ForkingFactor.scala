package io.iohk.scevm.network.metrics

import cats.effect.{Ref, Sync}
import cats.syntax.all._
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftHeader}
import io.iohk.scevm.ledger.BlockProvider

trait ForkingFactor[F[_]] {
  def evaluate(obftHeader: ObftHeader): F[Double]
}

object ForkingFactor {

  /** forkingFactor = unstableBlocks.length / k
    *   where unstableBlocks -> all blocks from all forks
    */
  def calculateForkingFactor(blocksMap: Map[BlockNumber, Set[BlockHash]], stabilityParameter: Int): Double = {
    val unstableBlocks: Double = blocksMap.values.toSet.flatten.size
    unstableBlocks / stabilityParameter.toDouble
  }
}

class ForkingFactorImpl[F[_]: Sync] private (
    blockProvider: BlockProvider[F],
    stabilityParameter: Int,
    private[metrics] val hashToNumberMap: Ref[F, Map[BlockNumber, Set[BlockHash]]]
) extends ForkingFactor[F] {
  def evaluate(obftHeader: ObftHeader): F[Double] =
    for {
      blocksMap    <- hashToNumberMap.get
      mapUpdated    = addNewBlock(blocksMap, obftHeader)
      stableHeader <- blockProvider.getStableBlockHeader
      // purge after adding the new block to make sure calculation is not updated with odd old blocks
      mapOnlyUnstable = filterAboveStable(mapUpdated, stableHeader.number)
      _              <- hashToNumberMap.set(mapOnlyUnstable)
    } yield ForkingFactor.calculateForkingFactor(mapOnlyUnstable, stabilityParameter)

  private def filterAboveStable(
      blocksMap: Map[BlockNumber, Set[BlockHash]],
      stableNumber: BlockNumber
  ): Map[BlockNumber, Set[BlockHash]] = blocksMap.filter { case (bn, _) => bn.value > stableNumber.value }

  private def addNewBlock(
      blocksMap: Map[BlockNumber, Set[BlockHash]],
      obftHeader: ObftHeader
  ): Map[BlockNumber, Set[BlockHash]] =
    blocksMap.updatedWith(obftHeader.number) {
      case Some(hashes) => Some(hashes + obftHeader.hash)
      case None         => Some(Set(obftHeader.hash))
    }
}

object ForkingFactorImpl {
  def apply[F[_]: Sync](
      blockProvider: BlockProvider[F],
      stabilityParameter: Int
  ): F[ForkingFactorImpl[F]] =
    for {
      hashToNumberMap <- Ref.of[F, Map[BlockNumber, Set[BlockHash]]](Map.empty)
    } yield new ForkingFactorImpl[F](blockProvider, stabilityParameter, hashToNumberMap)
}
