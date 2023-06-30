package io.iohk.scevm.sidechain.pos

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.consensus.pos.BlockPreExecution
import io.iohk.scevm.domain.Address
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.sidechain.{BridgeContract, SidechainEpochDerivation}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class SidechainBlockPreExecution[F[_]: Sync](
    bridgeContractAddress: Address,
    epochDerivation: SidechainEpochDerivation[F]
) extends BlockPreExecution[F] {

  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(this.getClass)

  override def prepare(state: InMemoryWorldState): F[InMemoryWorldState] = {
    val blockContext = state.blockContext
    logger.trace(show"Preparing the block state for ${blockContext.slotNumber}, ${blockContext.number}").map { _ =>
      val epoch = epochDerivation.getSidechainEpoch(blockContext.slotNumber)
      BridgeContract.prepareState(bridgeContractAddress)(
        state,
        epoch,
        epochDerivation.getSidechainEpochPhase(blockContext.slotNumber)
      )
    }
  }
}
