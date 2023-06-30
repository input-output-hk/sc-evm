package io.iohk.scevm.domain

import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.rlp
import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits.bigIntEncDec
import io.iohk.ethereum.rlp.RLPList

object ContractAddress {
  def fromSender(sender: Address, nonce: Nonce): Address = {
    val hash = kec256(rlp.encode(RLPList(sender.bytes, nonce)))
    Address(hash)
  }
}
