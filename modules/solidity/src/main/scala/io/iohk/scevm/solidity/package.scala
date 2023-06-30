package io.iohk.scevm

import cats.Show
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.UInt256

import scala.language.implicitConversions

package object solidity {

  @newtype final case class UInt8(value: UInt256)

  object UInt8 {
    implicit val show: Show[UInt8] = Show.show(uint8 => s"UInt8(${uint8.value.toHexString}")
  }

  final case class Bytes32 private (value: ByteString)

  object Bytes32 {

    /** There are no safe/unsafe variants. This class reflects solidity 'bytes32' type.
      *  If a programmer messes up and use it some other type, it's unrecoverable error.
      */
    def apply(bs: ByteString): Bytes32 = {
      require(bs.length == 32, s"Expected 32 bytes, but got ${bs.length}")
      new Bytes32(bs)
    }

    implicit final class ByteStringOps(bs: ByteString) {
      def asBytes32: Bytes32 = Bytes32(bs)
    }

    val Zeros: Bytes32 = Bytes32(ByteString(Array.fill(32)(0.toByte)))
  }
}
