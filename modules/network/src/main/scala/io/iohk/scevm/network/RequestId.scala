package io.iohk.scevm.network

import io.iohk.scevm.serialization.Newtype

/** Represents the request_id as introduced by ETH/66.
  * @see https://eips.ethereum.org/EIPS/eip-2481
  *
  * RequestId is set as a free value on request messages.
  * Response messages should include the provided request id.
  * @param value the internal value
  */
final case class RequestId(value: Long) extends AnyVal {
  override def toString: String = s"RequestId($value)"
}

object RequestId {
  implicit val valueClass: Newtype[RequestId, Long] =
    Newtype[RequestId, Long](RequestId.apply, _.value)
}
