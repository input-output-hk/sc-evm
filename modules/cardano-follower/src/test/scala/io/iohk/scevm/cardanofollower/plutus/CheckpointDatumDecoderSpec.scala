package io.iohk.scevm.cardanofollower.plutus

import io.circe.literal.JsonStringContext
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.trustlesssidechain.CheckpointDatum
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import DatumDecoderHelpers.decodeFromJson

class CheckpointDatumDecoderSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  "CheckpointDatum" should {
    "be decoded from json " in {

      val result = decodeFromJson[CheckpointDatum](
        json"""
          {
            "fields": [
              {"bytes": "d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"},
              {"int": 1113}
            ],
            "constructor": 0
          }"""
      )
      val expected = CheckpointDatum(
        sidechainBlockHash =
          BlockHash(Hex.decodeUnsafe("d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab")),
        sidechainBlockNumber = BlockNumber(1113)
      )

      assert(result == Right(expected))
    }
  }
}
