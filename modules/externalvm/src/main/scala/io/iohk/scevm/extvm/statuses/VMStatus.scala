package io.iohk.scevm.extvm.statuses

import io.iohk.scevm.domain.Receipt.StatusCode

/** Trait representing VM status.
  */
trait VMStatus {

  def isSuccessful: Boolean = !isFailed

  def isFailed: Boolean

  def statusCode: StatusCode

}
