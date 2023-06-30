package io.iohk.scevm.extvm.iele

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.Receipt.StatusCode
import io.iohk.scevm.extvm.statuses.VMStatus

/** Data type representing status of IELE program execution.
  *
  * @param code Status code
  */
final case class IELEStatus(code: ByteString) extends VMStatus {
  override def isFailed: Boolean = BigInt(code.toArray) != 0

  override def statusCode: StatusCode = StatusCode(code)
}
