package io.iohk.scevm.cardanofollower.plutus

import io.circe.literal.JsonStringContext
import io.iohk.ethereum.crypto.ECDSASignatureNoRecovery
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.trustlesssidechain.SignedMerkleRootRedeemer
import io.iohk.scevm.trustlesssidechain.cardano.EcdsaCompressedPubKey
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import DatumDecoderHelpers.decodeFromJson

class SignedMerkleRootRedeemerDecoderSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  "should be decoded from json " in {

    val result = decodeFromJson[SignedMerkleRootRedeemer](
      json"""
          {"fields":
            [
              {"bytes": "bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a"},
              {"fields":
                [
                 {"bytes": "be756969dd2e563675fa8fdb32d600d1108441ed03044f2cb6877c6e4561e843"}
                ],
                "constructor": 0
              },
              {"list":
                [
                  {"bytes": "0fb3c5700d5b75daad75e4bc57ea96e0f09132812b1d8f33c53d697deaeaefcd5c472f252cb9219ee7cc7528d755b2a825fd00788c333a60de2f264ef00ae53f"},
                  {"bytes": "312b0b8a5177a21280a6f316896781aa72ef8b6a6236b8fd70bbbe8407e7950805b7e3e583ca3d65071b6ebb5b20c1c408f4acd2006a05117b38f6d6ce019415"},
                  {"bytes": "53f4991b567497e32d77dfc48ca08f4720f070fe85fe9dfebcf7f4ef85fc1fdc00886aeccd10649e8d57a01708a42980f8d38f09829755b2b0541da727931b73"}
                ]
              },
              {"list":
                [
                  {"bytes": "0239bdbb177c51cd405659e39439a7fd65bc377c5b24a4684d335518fc5b618a16"},
                  {"bytes": "02d552ed9b1e49e243baf80603475f88a0dc1cbbdcc5f66afe171c021bb9705340"},
                  {"bytes": "02de4771f6630b8c6e945a604b3d91a9d266b78fef7c6b6ef72cead11e608e6eb9"}
               ]
              }
            ],
            "constructor": 0
          }"""
    )
    val expected = SignedMerkleRootRedeemer(
      Hex.decodeUnsafe("bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a"),
      Some(Hex.decodeUnsafe("be756969dd2e563675fa8fdb32d600d1108441ed03044f2cb6877c6e4561e843")),
      List(
        ECDSASignatureNoRecovery.fromHexUnsafe(
          "0fb3c5700d5b75daad75e4bc57ea96e0f09132812b1d8f33c53d697deaeaefcd5c472f252cb9219ee7cc7528d755b2a825fd00788c333a60de2f264ef00ae53f"
        ),
        ECDSASignatureNoRecovery.fromHexUnsafe(
          "312b0b8a5177a21280a6f316896781aa72ef8b6a6236b8fd70bbbe8407e7950805b7e3e583ca3d65071b6ebb5b20c1c408f4acd2006a05117b38f6d6ce019415"
        ),
        ECDSASignatureNoRecovery.fromHexUnsafe(
          "53f4991b567497e32d77dfc48ca08f4720f070fe85fe9dfebcf7f4ef85fc1fdc00886aeccd10649e8d57a01708a42980f8d38f09829755b2b0541da727931b73"
        )
      ),
      List(
        EcdsaCompressedPubKey(Hex.decodeUnsafe("0239bdbb177c51cd405659e39439a7fd65bc377c5b24a4684d335518fc5b618a16")),
        EcdsaCompressedPubKey(Hex.decodeUnsafe("02d552ed9b1e49e243baf80603475f88a0dc1cbbdcc5f66afe171c021bb9705340")),
        EcdsaCompressedPubKey(Hex.decodeUnsafe("02de4771f6630b8c6e945a604b3d91a9d266b78fef7c6b6ef72cead11e608e6eb9"))
      )
    )
    result shouldBe Right(expected)
  }
}
