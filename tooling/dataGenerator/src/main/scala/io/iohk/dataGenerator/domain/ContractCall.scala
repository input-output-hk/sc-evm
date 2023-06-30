package io.iohk.dataGenerator.domain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteStringUtils.RichByteString
import io.iohk.scevm.domain.{Address, ContractAddress, Nonce}

/** Must be deployed on block 1 for all senders at "nonce" */
final case class ContractCall(initCode: ByteString, callCode: ByteString, nonce: Nonce) {
  def contractAddress(sender: Address): Address = ContractAddress.fromSender(sender, nonce)

  override def toString: String = s"ContractCall($nonce, ${initCode.toHex}, ${callCode.toHex})"
}
