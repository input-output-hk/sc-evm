package io.iohk.scevm.network

import io.iohk.ethereum.rlp.RLPImplicits.longEncDec
import io.iohk.ethereum.rlp.{decode, encode}
import io.iohk.scevm.domain.{valueClassDec, valueClassEnc}
import io.iohk.scevm.testing.Generators
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class RequestIdSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "RequestId" should {
    "round trip via RLPx serialization" in {
      forAll(Generators.longGen) { requestIdValue =>
        val requestId            = RequestId(requestIdValue)
        val encoded: Array[Byte] = encode[RequestId](requestId)
        decode[RequestId](encoded) shouldEqual requestId
      }
    }
  }
}
