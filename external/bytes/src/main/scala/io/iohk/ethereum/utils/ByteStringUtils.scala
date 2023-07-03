package io.iohk.ethereum.utils

import io.iohk.bytes.ByteString

import scala.collection.mutable
import scala.math.Ordering.Implicits._

object ByteStringUtils {
  def hash2string(hash: ByteString): String =
    Hex.toHexString(hash.toArray[Byte])

  implicit class RichByteString(val hash: ByteString) {
    def toHex: String = hash2string(hash)
  }

  implicit final class Padding(val bs: ByteString) extends AnyVal {
    def padToByteString(length: Int, b: Byte): ByteString =
      if (length <= bs.length) bs
      else {
        val len    = Math.max(bs.length, length)
        val result = new Array[Byte](len)
        bs.copyToArray(result, 0)
        var i = bs.length
        while (i < len) {
          result.update(i, b)
          i += 1
        }
        ByteString.fromArray(result)
      }
  }

  sealed trait ByteStringElement {
    def len: Int
    def asByteArray: Array[Byte]
  }

  implicit final class ByteStringSelfElement(val bs: ByteString) extends ByteStringElement {
    def len: Int                 = bs.length
    def asByteArray: Array[Byte] = bs.toArray
  }

  implicit final class ByteStringArrayElement(val ar: Array[Byte]) extends ByteStringElement {
    def len: Int                 = ar.length
    def asByteArray: Array[Byte] = ar
  }

  implicit final class ByteStringByteElement(val b: Byte) extends ByteStringElement {
    def len: Int                 = 1
    def asByteArray: Array[Byte] = Array(b)
  }

  implicit val byteStringOrdering: Ordering[ByteString] =
    Ordering.by[ByteString, Seq[Byte]](_.toSeq)

  def concatByteStrings(head: ByteStringElement, tail: ByteStringElement*): ByteString = {
    val it = Iterator.single(head) ++ tail.iterator
    concatByteStrings(it)
  }

  def concatByteStrings(elements: Iterator[ByteStringElement]): ByteString = {
    val builder = new mutable.ArrayBuilder.ofByte
    elements.foreach(el => builder ++= el.asByteArray)
    ByteString(builder.result())
  }

}
