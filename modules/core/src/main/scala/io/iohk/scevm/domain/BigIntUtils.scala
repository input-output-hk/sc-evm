package io.iohk.scevm.domain

import io.iohk.bytes.ByteString

object BigIntUtils {
  private def Zeros(size: Int): ByteString = ByteString(Array.fill[Byte](size)(0))

  implicit class BigIntOps(value: BigInt) {
    def bytes(size: Int): ByteString = {
      val bs: ByteString = ByteString(value.toByteArray).takeRight(size)
      val padLength: Int = size - bs.length
      if (padLength > 0)
        Zeros(size).take(padLength) ++ bs
      else
        bs
    }
  }
}
