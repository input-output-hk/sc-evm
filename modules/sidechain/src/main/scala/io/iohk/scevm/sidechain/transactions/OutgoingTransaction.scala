package io.iohk.scevm.sidechain.transactions

import cats.Show
import io.iohk.scevm.domain.Token

final case class OutgoingTransaction(value: Token, recipient: OutgoingTxRecipient, txIndex: OutgoingTxId)
object OutgoingTransaction {
  implicit val show: Show[OutgoingTransaction] = cats.derived.semiauto.show
}
