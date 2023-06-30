package io.iohk.scevm.serialization

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp.{decode, encode}

object Implicits {

  implicit val byteStringSerializer: ByteArraySerializable[ByteString] = new ByteArraySerializable[ByteString] {
    override def toBytes(input: ByteString): Array[Byte]   = input.toArray[Byte]
    override def fromBytes(bytes: Array[Byte]): ByteString = ByteString(bytes)
  }

  implicit val byteArraySerializable: ByteArraySerializable[Array[Byte]] = new ByteArraySerializable[Array[Byte]] {
    override def toBytes(input: Array[Byte]): Array[Byte]   = input
    override def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes
  }

  implicit val intByteArraySerializable: ByteArraySerializable[Int] = new ByteArraySerializable[Int] {
    override def fromBytes(bytes: Array[Byte]): Int = decode[Int](bytes)
    override def toBytes(input: Int): Array[Byte]   = encode(input)
  }

  implicit val bigIntByteArraySerializable: ByteArraySerializable[BigInt] = new ByteArraySerializable[BigInt] {
    override def fromBytes(bytes: Array[Byte]): BigInt = BigInt(bytes)
    override def toBytes(input: BigInt): Array[Byte]   = input.toByteArray
  }

}
