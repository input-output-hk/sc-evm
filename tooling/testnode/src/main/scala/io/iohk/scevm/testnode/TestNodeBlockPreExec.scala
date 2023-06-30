package io.iohk.scevm.testnode

import cats.effect.kernel.Sync
import io.iohk.scevm.consensus.pos.BlockPreExecution
import io.iohk.scevm.domain.{Address, MemorySlot, Token}
import io.iohk.scevm.exec.vm.InMemoryWorldState

// This class leverages the BlockPreExecution mechanism to be able to inject
// values into the storage.
// It's a bit of a hack and can't know what modification to make at which slots
// so when it is used we are not able to reexecute older block. This should not
// be a problem in test mode.
class TestNodeBlockPreExec[F[_]: Sync] extends BlockPreExecution[F] {

  private var storageModifications: Map[(Address, BigInt), BigInt] = Map.empty

  def addStorageModification(address: Address, memorySlot: MemorySlot, value: Token): Unit =
    storageModifications = storageModifications.updated((address, memorySlot.value), value.value)

  def clear(): Unit = storageModifications = Map.empty

  override def prepare(initialState: InMemoryWorldState): F[InMemoryWorldState] =
    Sync[F].delay {
      storageModifications.foldLeft(initialState) { case (state, (address, memorySlot) -> value) =>
        val newStorage = state.getStorage(address).store(memorySlot, value)
        state.saveStorage(address, newStorage)
      }
    }

}
