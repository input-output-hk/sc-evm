package io.iohk.scevm.sidechain.consensus

import cats.Applicative
import cats.implicits.{catsSyntaxApplicativeId, showInterpolator}
import io.iohk.scevm.consensus.pos.ObftBlockExecution
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.domain.{BlockNumber, EpochPhase, ObftHeader}
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.sidechain.SidechainEpochDerivation

/** Block number 1 cannot be from the Handover phase slot, because it's illegal to start chain in that phase.
  * It's a PostExecutionValidator because it's easy to add it to the existing validators chain,
  * but it doesn't depend on the execution result.
  */
class FirstBlockIsNotFromHandoverPhaseValidator[F[_]: Applicative](epochDerivation: SidechainEpochDerivation[F])
    extends PostExecutionValidator[F] {
  override def validate(
      s: WorldType,
      header: ObftHeader,
      r: ObftBlockExecution.BlockExecutionResult
  ): F[Either[PostExecutionValidator.PostExecutionError, Unit]] = {
    val isFirstBlock        = header.number == BlockNumber(1)
    val isFromHandoverPhase = epochDerivation.getSidechainEpochPhase(header.slotNumber) == EpochPhase.Handover
    Either
      .cond(
        !(isFirstBlock && isFromHandoverPhase),
        (),
        PostExecutionError(header.hash, show"Block $header is from Handover phase")
      )
      .pure
  }
}
