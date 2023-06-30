package io.iohk.scevm.rpc.domain

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, Gas, LegacyTransaction, Nonce, Token}
import io.iohk.scevm.rpc.domain.TransactionRequest.{DefaultGas, DefaultGasPrice}

final case class TransactionRequest(
    from: Address,
    to: Option[Address] = None,
    value: Option[Token] = None,
    gas: Option[Gas] = None,
    gasPrice: Option[Token] = None,
    nonce: Option[Nonce] = None,
    data: Option[ByteString] = None
) {
  def toTransaction(defaultNonce: Nonce): LegacyTransaction =
    LegacyTransaction(
      nonce = nonce.getOrElse(defaultNonce),
      gasPrice = gasPrice.fold(DefaultGasPrice)(_.value),
      gasLimit = gas.fold(DefaultGas)(_.value),
      receivingAddress = to,
      value = value.fold(BigInt(0))(_.value),
      payload = data.getOrElse(ByteString.empty)
    )
}

object TransactionRequest {
  val DefaultGasPrice: BigInt = 0
  val DefaultGas: BigInt      = 90000
}
