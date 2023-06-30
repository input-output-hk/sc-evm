package io.iohk.scevm.network.p2p

import io.iohk.ethereum.rlp.RLPSerializable

trait Message extends RLPSerializable {
  def code: Int
  def toShortString: String = toString
}
