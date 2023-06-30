package io.iohk.scevm.domain

import io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ObftHeaderUnsignedSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "ObftHeaderUnsigned" when {
    "createWithPrivateKey" should {
      "sign" in {
        forAll(ecdsaKeyPairGen, obftBlockHeaderGen) { case ((prvKey, pubKey), dummyHeader) =>
          val signedHeader = ObftHeaderUnsigned(
            parentHash = dummyHeader.parentHash,
            number = dummyHeader.number,
            slotNumber = dummyHeader.slotNumber,
            beneficiary = dummyHeader.beneficiary,
            stateRoot = dummyHeader.stateRoot,
            transactionsRoot = dummyHeader.transactionsRoot,
            receiptsRoot = dummyHeader.receiptsRoot,
            logsBloom = dummyHeader.logsBloom,
            gasLimit = dummyHeader.gasLimit,
            gasUsed = dummyHeader.gasUsed,
            unixTimestamp = dummyHeader.unixTimestamp,
            publicSigningKey = pubKey
          ).sign(prvKey)

          signedHeader.signature.publicKey(signedHeader.hashWithoutSignature) shouldBe Some(
            signedHeader.publicSigningKey.bytes
          )
        }
      }
    }
  }

}
