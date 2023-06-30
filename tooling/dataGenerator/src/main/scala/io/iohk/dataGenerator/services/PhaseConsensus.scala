package io.iohk.dataGenerator.services

import cats.effect.{IO, Resource}
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.ConsensusService.BetterBranch
import io.iohk.scevm.consensus.pos.CurrentBranchService
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.ledger.BlockImportService.ImportedBlock

class PhaseConsensus private (stabilityParameter: Int)(currentBranchService: CurrentBranchService[IO]) {

  /** Add stable blocks (all chain except last k blocks) to the stable mappings */
  def run(importedBlocks: fs2.Stream[IO, ImportedBlock]): IO[(ObftBlock, ObftBlock, List[ObftBlock])] =
    importedBlocks
      .sliding(stabilityParameter + 1, 1)
      .evalMap { chunk =>
        chunk.toList match {
          case stable +: unstables :+ best =>
            currentBranchService
              .newBranch(BetterBranch(Vector(stable.block), best.header))
              .as((stable.block, best.block, (unstables :+ best).map(_.block)))
          case _ =>
            IO.raiseError(new RuntimeException("The generated chain is smaller than 2 blocks"))
        }
      }
      .compile
      .last
      .map(_.get)

  /** Add unstable k blocks to stable mappings */
  def stabilize(unstables: List[ObftBlock]): IO[Unit] =
    unstables.traverse_ { block =>
      currentBranchService
        .newBranch(BetterBranch(Vector(block), block.header))
    }
}

object PhaseConsensus {
  def build(blockchainConfig: BlockchainConfig)(
      currentBranchService: CurrentBranchService[IO]
  ): Resource[IO, PhaseConsensus] =
    Resource.pure(new PhaseConsensus(blockchainConfig.stabilityParameter)(currentBranchService))
}
