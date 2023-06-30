package io.iohk.scevm.domain

import io.iohk.bytes.ByteString

sealed trait TransactionOutcome

object TransactionOutcome {
  final case class HashOutcome(stateHash: ByteString) extends TransactionOutcome
  final case object SuccessOutcome                    extends TransactionOutcome
  final case object FailureOutcome                    extends TransactionOutcome
}
