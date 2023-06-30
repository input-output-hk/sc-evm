package io.iohk.scevm.wallet

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, SidechainPrivateKey, SignedTransaction, Transaction}

final case class Wallet(address: Address, prvKey: SidechainPrivateKey) {

  def signTx(tx: Transaction, chainId: Option[ChainId]): SignedTransaction =
    SignedTransaction.sign(tx, prvKey, chainId)
}
