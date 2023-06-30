package io.iohk.scevm.domain

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

trait SignedTransactionBehavior extends Matchers with ScalaCheckPropertyChecks {
  this: AnyWordSpec =>

  def SignedTransactionBehavior(
      signedTransactionGenerator: Gen[Transaction],
      allowedPointSigns: Byte => Set[Byte]
  ): Unit =
    "correctly set pointSign for chainId with chain specific signing schema" in {
      forAll(signedTransactionGenerator, ecdsaKeyPairGen) { case (tx, (priv, _)) =>
        val chainId: ChainId = ChainId(0x3d)
        val result           = SignedTransaction.sign(tx, priv, Some(chainId))

        allowedPointSigns(chainId.value) should contain(result.signature.v)
      }
    }
}
