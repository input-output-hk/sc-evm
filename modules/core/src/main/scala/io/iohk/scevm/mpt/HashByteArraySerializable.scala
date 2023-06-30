package io.iohk.scevm.mpt

import io.iohk.ethereum.crypto.kec256
import io.iohk.scevm.serialization.ByteArrayEncoder

final case class HashByteArraySerializable[T](tSerializer: ByteArrayEncoder[T]) extends ByteArrayEncoder[T] {
  override def toBytes(input: T): Array[Byte] = kec256(tSerializer.toBytes(input))
}
