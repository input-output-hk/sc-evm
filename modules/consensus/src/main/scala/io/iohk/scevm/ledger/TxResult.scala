package io.iohk.scevm.ledger

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.TransactionLogEntry
import io.iohk.scevm.exec.vm.{InMemoryWorldState, ProgramError}

final case class TxResult(
    worldState: InMemoryWorldState,
    gasUsed: BigInt,
    logs: Seq[TransactionLogEntry],
    vmReturnData: ByteString,
    vmError: Option[ProgramError]
)
