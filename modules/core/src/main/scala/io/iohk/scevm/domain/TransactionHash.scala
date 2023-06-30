package io.iohk.scevm.domain

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteStringUtils
import io.iohk.scevm.serialization.Newtype

final case class TransactionHash(byteString: ByteString) extends AnyVal {
  def toHex: String = ByteStringUtils.hash2string(byteString)
}

object TransactionHash {
  implicit val valueClass: Newtype[TransactionHash, ByteString] =
    Newtype[TransactionHash, ByteString](TransactionHash.apply, _.byteString)

  implicit val show: Show[TransactionHash] =
    Show.show(t => show"TransactionHash(${t.toHex})")
}
