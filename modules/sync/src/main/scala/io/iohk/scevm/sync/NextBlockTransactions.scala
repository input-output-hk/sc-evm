package io.iohk.scevm.sync

import io.iohk.scevm.domain.{LeaderSlotEvent, ObftHeader, SignedTransaction, Slot}

trait NextBlockTransactions[F[_]] {

  /** Gets the next transaction that can be included in a block. The returned transactions are already
    * properly ordered for inclusion.
    */
  def getNextTransactions(
      slot: Slot,
      privateKey: LeaderSlotEvent.KeySet,
      best: ObftHeader
  ): F[Either[String, List[SignedTransaction]]]
}
