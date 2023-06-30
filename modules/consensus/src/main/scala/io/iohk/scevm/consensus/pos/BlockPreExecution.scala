package io.iohk.scevm.consensus.pos

import cats.Applicative
import io.iohk.scevm.exec.vm.InMemoryWorldState

/** Actions to do before executing a block */
trait BlockPreExecution[F[_]] {

  /** modify the given state to return the initial state at the beginning of execution */
  def prepare(state: InMemoryWorldState): F[InMemoryWorldState]

}

object BlockPreExecution {

  def noop[F[_]: Applicative]: BlockPreExecution[F] = new BlockPreExecution[F] {
    override def prepare(state: InMemoryWorldState): F[InMemoryWorldState] =
      Applicative[F].pure(state)
  }

}
