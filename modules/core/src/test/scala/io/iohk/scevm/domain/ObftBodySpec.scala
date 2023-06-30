package io.iohk.scevm.domain

import io.iohk.ethereum.rlp._
import io.iohk.scevm.testing.BlockGenerators.obftBlockBodyGen
import io.iohk.scevm.testing.Generators.emptyStorageRoot
import org.scalacheck.Arbitrary
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ObftBodySpec extends AnyFreeSpec with Matchers with ScalaCheckPropertyChecks {
  implicit val arbObftBody: Arbitrary[ObftBody] = Arbitrary(obftBlockBodyGen)

  "RLP encoding of the ObftBody" - {
    "should be symmetric" in {
      forAll { body: ObftBody =>
        val encoded: Array[Byte] = encode(body)
        val decoded              = decode[ObftBody](encoded)

        decoded shouldBe body
      }
    }
  }

  "transactionsRoot" - {
    "match header hash for empty blocks" in {
      ObftBody.empty.transactionsRoot shouldEqual emptyStorageRoot
    }

    "should uniquely hash block bodies" in {
      forAll { (body1: ObftBody, body2: ObftBody) =>
        whenever(body1 != body2) {
          (body1.transactionsRoot should not).equal(body2.transactionsRoot)
        }
      }
    }
  }

}
