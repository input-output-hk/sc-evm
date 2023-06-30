package io.iohk.scevm.sidechain

import cats.Show
import io.iohk.scevm.cardanofollower.plutus.TransactionRecipientDatum
import io.iohk.scevm.domain.Token
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainTxHash}

final case class IncomingCrossChainTransaction(
    recipientDatum: TransactionRecipientDatum,
    value: Token,
    txId: MainchainTxHash,
    stableAtBlock: MainchainBlockNumber
)

object IncomingCrossChainTransaction {
  implicit val show: Show[IncomingCrossChainTransaction] = cats.derived.semiauto.show
}
