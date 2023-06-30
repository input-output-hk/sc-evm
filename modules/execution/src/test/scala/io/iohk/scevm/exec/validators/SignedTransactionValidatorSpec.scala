package io.iohk.scevm.exec.validators

import io.iohk.scevm.domain.{Account, BlockNumber, Nonce, UInt256}
import io.iohk.scevm.testing.TestCoreConfigs
import io.iohk.scevm.testing.TransactionGenerators.{legacyTransactionGen, signedTxGenWithTx}
import org.scalacheck.Gen
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SignedTransactionValidatorSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "SignedTransactionValidatorImpl.validateForMempool" should {
    "should allow transactions with the higher nonce" in {
      val gen = for {
        legacyTx    <- legacyTransactionGen.map(_.copy(gasLimit = 25000))
        stx         <- signedTxGenWithTx(legacyTx)
        senderNonce <- intNonceGen(stx.transaction.nonce)
      } yield (stx, Account(senderNonce, UInt256.MaxValue))

      forAll(gen) { case (stx, account) =>
        SignedTransactionValidatorImpl.validateForMempool(
          stx,
          account,
          BlockNumber(0),
          stx.transaction.gasLimit + 1,
          0
        )(
          TestCoreConfigs.blockchainConfig
        ) mustBe Right(SignedTransactionValid)
      }
    }
  }

  def intNonceGen(max: Nonce): Gen[Nonce] = {
    val maxInt = if (max.value > Int.MaxValue) Int.MaxValue else max.value.toInt
    Gen.chooseNum(0, maxInt).map(Nonce(_))
  }

}
