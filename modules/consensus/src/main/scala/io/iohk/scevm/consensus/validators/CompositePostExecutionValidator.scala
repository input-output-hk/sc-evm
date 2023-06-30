package io.iohk.scevm.consensus.validators

import cats.data.EitherT
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.exec.vm.WorldType

class CompositePostExecutionValidator[F[_]: Sync](validators: List[PostExecutionValidator[F]])
    extends PostExecutionValidator[F] {

  override def validate(
      initialState: WorldType,
      header: ObftHeader,
      execResult: BlockExecutionResult
  ): F[Either[PostExecutionValidator.PostExecutionError, Unit]] =
    validators.traverse(v => EitherT(v.validate(initialState, header, execResult))).void.value
}
