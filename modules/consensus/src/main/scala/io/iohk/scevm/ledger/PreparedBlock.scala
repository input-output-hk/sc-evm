package io.iohk.scevm.ledger

import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.SignedTransaction
import io.iohk.scevm.exec.vm.InMemoryWorldState

final case class PreparedBlock(
    transactionList: Seq[SignedTransaction],
    blockResult: BlockExecutionResult,
    stateRootHash: ByteString,
    updatedWorld: InMemoryWorldState
)
