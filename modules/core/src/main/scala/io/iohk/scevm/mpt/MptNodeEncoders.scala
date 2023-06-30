package io.iohk.scevm.mpt

import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp._

object MptNodeEncoders {
  val BranchNodeChildLength  = 16
  val BranchNodeIndexOfValue = 16
  val ExtensionNodeLength    = 2
  val LeafNodeLength         = 2
  val MaxNodeValueSize       = 31
  val HashLength             = 32

  implicit class MptNodeEnc(obj: MptNode) extends RLPSerializable {
    def toRLPEncodable: RLPEncodeable = MptTraversals.encode(obj)
  }

  implicit class MptNodeDec(val bytes: Array[Byte]) extends AnyVal {
    def toMptNode: MptNode = MptTraversals.decodeNode(bytes)
  }

  implicit class MptNodeRLPEncodableDec(val rlp: RLPEncodeable) extends AnyVal {
    def toMptNode: MptNode = MptTraversals.decodeNode(rlp)
  }
}
