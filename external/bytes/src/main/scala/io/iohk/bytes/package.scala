package io.iohk

import cats.{Eq, Show}
import io.iohk.ethereum.utils.Hex

package object bytes {
  type ByteString = akka.util.ByteString
  implicit val showForByteString: Show[ByteString] = Show.show[ByteString](Hex.toHexString)

  object ByteString {
    implicit val eqForByteString: Eq[ByteString]                   = Eq.fromUniversalEquals
    def apply(arr: Array[Byte]): ByteString                        = akka.util.ByteString.apply(arr)
    def apply(arr: Byte*): ByteString                              = akka.util.ByteString.apply(arr)
    def apply(bytes: IterableOnce[Byte]): ByteString               = akka.util.ByteString.apply(bytes)
    def apply[T](bytes: T*)(implicit num: Integral[T]): ByteString = akka.util.ByteString.apply(bytes: _*)
    def apply(s: String): ByteString                               = akka.util.ByteString.apply(s)
    def empty: ByteString                                          = akka.util.ByteString.empty
    def fromArray(arr: Array[Byte]): ByteString                    = akka.util.ByteString.fromArray(arr)
    def fromArrayUnsafe(arr: Array[Byte]): ByteString              = akka.util.ByteString.fromArrayUnsafe(arr)
    def fromInts(array: Int*): ByteString                          = akka.util.ByteString.fromInts(array: _*)
    def fromString(str: String): ByteString                        = akka.util.ByteString(str)
  }

  trait FromHex[T] {
    def apply(hex: String): T
  }

  trait FromBytes[T] {
    def apply(bytes: ByteString): T
  }
}
