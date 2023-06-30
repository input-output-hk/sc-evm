package io.iohk.scevm.node

import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranchService}
import io.iohk.scevm.db.storage.{GetByNumberService, ObftStorageBuilder}
import io.iohk.scevm.domain.SignedTransaction
import io.iohk.scevm.ledger.{BlockImportService, SlotDerivation}
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import io.iohk.scevm.storage.execution.SlotBasedMempool

/** This trait expose some internals to be able to manipulate a SC EVM node.
  * It is mostly useful in test mode to be able to stop a node and manipulate it.
  */
trait ScEvmNode[F[_]] {
  def ready: F[Unit]
  def routes: RpcRoutes[F]
  def currentBranchService: CurrentBranchService[F]
  def getByNumberService: GetByNumberService[F]
  // The mempool is exposed because we want to clear transaction when rewinding the chain in ETS
  // If they are cleared on execution we will probably be able to remove this
  def transactionMempool: SlotBasedMempool[F, SignedTransaction]
  def consensusService: ConsensusService[F]
  def obftImportService: BlockImportService[F]
  def storage: ObftStorageBuilder[F]
  def slotDerivation: SlotDerivation
}
