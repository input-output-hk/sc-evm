package io.iohk.scevm.exec.vm

import io.iohk.scevm.domain.{Address, UInt256}

trait VM[F[_], W <: WorldState[W, S], S <: Storage[S]] {
  type PC = ProgramContext[W, S]
  type PR = ProgramResult[W, S]
  type PS = ProgramState[W, S]

  def run(context: PC): F[PR]
}

object VM {

  type Call[W <: WorldState[W, S], S <: Storage[S]] = (ProgramContext[W, S], Address) => ProgramResult[W, S]

  type Create[W <: WorldState[W, S], S <: Storage[S]] =
    (ProgramContext[W, S], Option[UInt256]) => (ProgramResult[W, S], Address)

  type SelfDestruct[W <: WorldState[W, S], S <: Storage[S]] =
    (ProgramContext[W, S], ProgramState[W, S]) => ProgramState[W, S]
}
