package io.iohk.scevm.extvm.testevm

import io.iohk.bytes.ByteString

/** 256-bit hash, (SHA-1 or Keccak-256)
  * @param toByteString - fixed-length (= 32) byte array representation
  */
final case class Hash private (toByteString: ByteString) extends AnyVal {
  def toByteArray: Array[Byte]       = toByteString.toArray[Byte]
  def toIndexedSeq: IndexedSeq[Byte] = toByteString
}
