package io.iohk.scevm.exec.utils

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, BlockContext, LegacyTransaction, Nonce, Transaction}
import io.iohk.scevm.testing.fixtures._

object MockVmInput {

  val defaultGasPrice: BigInt = 1000

  // scalastyle:off parameter.number
  def mockTransaction(
      payload: ByteString,
      value: BigInt,
      gasLimit: BigInt,
      gasPrice: BigInt = defaultGasPrice,
      receivingAddress: Option[Address] = None,
      nonce: Nonce = Nonce.Zero
  ): Transaction =
    LegacyTransaction(nonce, gasPrice, gasLimit, receivingAddress, value, payload)

  def blockContext: BlockContext = ValidBlock.blockContext

}
