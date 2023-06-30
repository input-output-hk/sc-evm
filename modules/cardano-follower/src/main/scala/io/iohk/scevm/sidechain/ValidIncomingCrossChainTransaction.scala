package io.iohk.scevm.sidechain

import cats.Show
import io.iohk.scevm.domain.{Address, Token}
import io.iohk.scevm.trustlesssidechain.cardano._

final case class ValidIncomingCrossChainTransaction(
    recipient: Address,
    value: Token,
    txId: MainchainTxHash,
    stableAtMainchainBlock: MainchainBlockNumber
)

object ValidIncomingCrossChainTransaction {
  implicit val show: Show[ValidIncomingCrossChainTransaction] = cats.derived.semiauto.show
}
