package io.iohk.scevm

import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.{Address, LegacyTransaction, Nonce, SignedTransaction}
import io.iohk.scevm.sidechain.BridgeContract
import io.iohk.scevm.sidechain.transactions.OutgoingTxRecipient
import io.iohk.scevm.solidity.{SolidityAbi, SolidityAbiEncoder}

object LockingTransaction {
  private val TOKEN_CONVERSION_RATE = BigInt(10).pow(9) // 1e9

  implicit private lazy val recipientSolidityEncoder: SolidityAbiEncoder[OutgoingTxRecipient] =
    OutgoingTxRecipient.deriving
  private def lockingPayload(outgoingTxRecipient: OutgoingTxRecipient) =
    SolidityAbi.solidityCall(BridgeContract.Methods.Lock, outgoingTxRecipient)

  def create(
      nonce: Nonce,
      bridgeContractAddress: Address,
      transactionSigningKey: ECDSA.PrivateKey,
      outgoingTxRecipient: OutgoingTxRecipient,
      gasLimit: BigInt
  ): SignedTransaction =
    SignedTransaction.sign(
      tx = LegacyTransaction(
        nonce = nonce,
        gasPrice = BigInt(1),
        gasLimit = gasLimit,
        receivingAddress = bridgeContractAddress,
        value = (2 * TOKEN_CONVERSION_RATE).toLong,
        payload = lockingPayload(outgoingTxRecipient)
      ),
      key = transactionSigningKey,
      chainId = None
    )

}
