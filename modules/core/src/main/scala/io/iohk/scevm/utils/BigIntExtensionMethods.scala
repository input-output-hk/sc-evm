package io.iohk.scevm.utils

import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.domain.UInt256

object BigIntExtensionMethods {
  implicit class BigIntAsUnsigned(val srcBigInteger: BigInt) extends AnyVal {
    def toUnsignedByteArray: Array[Byte] =
      ByteUtils.bigIntToUnsignedByteArray(srcBigInteger)

    def u256: UInt256 = UInt256(srcBigInteger)
  }
}
