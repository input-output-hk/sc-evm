package io.iohk.scevm.consensus.pos

import cats.syntax.all._
import cats.{Applicative, Monad}
import io.iohk.scevm.consensus.metrics.BlockMetrics
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.exec.vm.InMemoryWorldState

class MonitoredObftBlockExecution[F[_]: Monad](monitored: ObftBlockExecution[F], blockMetrics: BlockMetrics[F])
    extends ObftBlockExecution[F] {
  override def executeBlock(
      block: ObftBlock,
      state: InMemoryWorldState
  ): F[Either[ObftBlockExecution.BlockExecutionError, ObftBlockExecution.BlockExecutionResult]] =
    blockMetrics.durationOfBlockExecutionHistogram
      .observe(monitored.executeBlock(block, state))
      .flatTap {
        case Left(_)  => blockMetrics.blockExecutionErrorCounter.inc
        case Right(_) => Applicative[F].unit
      }
}
