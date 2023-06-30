package io.iohk.scevm.serialization

trait ByteArrayEncoder[T] {
  def toBytes(input: T): Array[Byte]
}

trait ByteArrayDecoder[T] {
  def fromBytes(bytes: Array[Byte]): T
}

trait ByteArraySerializable[T] extends ByteArrayEncoder[T] with ByteArrayDecoder[T]
