package io.iohk.scevm.cardanofollower.plutus

import io.circe.literal.JsonStringContext
import io.iohk.scevm.trustlesssidechain._
import io.iohk.scevm.trustlesssidechain.cardano.Blake2bHash32
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import DatumDecoderHelpers.decodeFromJson

class CommitteeDatumDecoderSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  "CommitteeDatum" should {
    "be decoded from json " in {

      val result = decodeFromJson[CommitteeDatum](
        json"""
          {
            "fields": [
              {"bytes": "d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"},
              {"int": 1113}
            ],
            "constructor": 0
          }"""
      )
      val expected = CommitteeDatum(
        Blake2bHash32.fromHexUnsafe("d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"),
        1113
      )

      result shouldBe Right(expected)
    }
  }
}
