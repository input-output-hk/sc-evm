package io.iohk.scevm.consensus.testing

import io.iohk.scevm.consensus.pos.ConsensusService
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{ObftBlock, ObftHeader}
import io.iohk.scevm.testing.BlockGenerators.{obftBlockBodyGen, obftBlockHeaderGen}
import org.scalacheck.Gen

object BetterBranchGenerators {
  def singleElementBetterBranchGen(startingBlock: ObftHeader): Gen[ConsensusService.BetterBranch] =
    for {
      header  <- obftBlockHeaderGen.map(h => h.copy(parentHash = startingBlock.hash, number = startingBlock.number.next))
      body    <- obftBlockBodyGen
      newBlock = ObftBlock(header, body)
    } yield ConsensusService.BetterBranch(Vector(newBlock), header)

}
