package io.iohk.scevm.domain

import cats.Show
import cats.kernel.Eq
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteStringUtils
import io.iohk.scevm.serialization.Newtype

final case class BlockHash(byteString: ByteString) extends AnyVal {
  def toHex: String = ByteStringUtils.hash2string(byteString)

  override def toString: String = Show[BlockHash].show(this)
}

object BlockHash {
  implicit val valueClass: Newtype[BlockHash, ByteString] =
    Newtype[BlockHash, ByteString](BlockHash.apply, _.byteString)
  implicit val eq: Eq[BlockHash] = Eq.fromUniversalEquals

  implicit val show: Show[BlockHash] = cats.derived.semiauto.show
}
