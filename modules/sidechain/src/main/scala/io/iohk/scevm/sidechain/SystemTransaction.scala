package io.iohk.scevm.sidechain

import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, Nonce, Transaction, TransactionType02}

/** A transaction created by a sidechain validator to handle various events.  Having this special
  * kind of transaction allows to defer choosing the nonce  and signing until we aggregated all
  * [[SystemTransaction]].
  */
final case class SystemTransaction(
    chainId: ChainId,
    gasLimit: BigInt,
    receivingAddress: Address,
    value: BigInt,
    payload: ByteString
) {

  def toTransaction(nonce: Nonce): Transaction = TransactionType02(
    chainId = chainId.value,
    nonce = nonce,
    maxPriorityFeePerGas = 0,
    maxFeePerGas = 0,
    gasLimit = gasLimit,
    receivingAddress = Some(receivingAddress),
    value = value,
    payload = payload,
    accessList = Nil
  )
}
