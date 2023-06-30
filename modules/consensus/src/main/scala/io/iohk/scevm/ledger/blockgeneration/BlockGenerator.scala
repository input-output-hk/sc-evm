package io.iohk.scevm.ledger.blockgeneration

import io.iohk.scevm.domain.{ObftBlock, ObftHeader, SidechainPrivateKey, SidechainPublicKey, SignedTransaction, Slot}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

trait BlockGenerator[F[_]] {
  def generateBlock(
      parent: ObftHeader,
      transactionList: Seq[SignedTransaction],
      slotNumber: Slot,
      timestamp: UnixTimestamp,
      validatorPubKey: SidechainPublicKey,
      validatorPrvKey: SidechainPrivateKey
  ): F[ObftBlock]
}
