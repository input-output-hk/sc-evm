package io.iohk.scevm.domain

import io.iohk.scevm.testing.TransactionGenerators.typedTransactionGen
import org.scalatest.wordspec.AnyWordSpec

class SignedTypedTransactionSpec extends AnyWordSpec with SignedTransactionBehavior {

  private val allowedPointSigns = Set(0.toByte, 1.toByte)

  "Signed TypedTransaction" should behave.like(
    SignedTransactionBehavior(typedTransactionGen, _ => allowedPointSigns)
  )
}
