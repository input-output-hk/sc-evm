package io.iohk.scevm.serialization

import io.iohk.ethereum.crypto.kec256

final case class HashByteArrayEncoder[T](tSerializer: ByteArrayEncoder[T]) extends ByteArrayEncoder[T] {
  override def toBytes(input: T): Array[Byte] = kec256(tSerializer.toBytes(input))
}
