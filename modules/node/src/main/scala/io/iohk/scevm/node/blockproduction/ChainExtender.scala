package io.iohk.scevm.node.blockproduction

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{Slot, SlotEvent}

import ChainExtender.{ChainExtensionError, ChainExtensionResult}

trait ChainExtender[F[_]] {

  /** Produces a block and extends the chain with it if node is a slot leader.
    * Returns error if node was a leader but chain wasn't extended.
    * Extending a chain is a process consisting of:
    * 1. Generating a block
    * 2. Persisting it
    * 3. Broadcasting it
    */
  def extendChainIfLeader(
      slotEvent: SlotEvent,
      currentBranch: CurrentBranch
  ): F[Either[ChainExtensionError, ChainExtensionResult]]
}

object ChainExtender {
  sealed trait ChainExtensionError extends ChainExtensionResult

  object ChainExtensionError {
    final case class NoTransactionsError(causeMsg: String, slot: Slot) extends ChainExtensionError

    final case class CannotImportOwnBlock(slot: Slot) extends ChainExtensionError

    final case class ConsensusError(causeMsg: String, slot: Slot) extends ChainExtensionError

    implicit val errorShow: Show[ChainExtensionError] = Show.show[ChainExtensionError] {
      case NoTransactionsError(causeMsg, slot) =>
        show"Not producing block in $slot slot, because next transactions provider failed with: $causeMsg"
      case CannotImportOwnBlock(slot) =>
        show"Fail to import previously generated block in $slot slot, it should almost never happen"
      case ConsensusError(causeMsg, slot) =>
        show"Consensus service failed to resolve newly generated block in $slot slot with: $causeMsg"
    }
  }
  sealed trait ChainExtensionResult
  object ChainExtensionResult {
    case object ChainExtended    extends ChainExtensionResult
    case object ChainNotExtended extends ChainExtensionResult
  }
}
