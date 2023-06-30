package io.iohk.scevm.cardanofollower.plutus

import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import io.bullet.borer.Cbor
import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.testing.CardanoFollowerDiffxInstances._
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.DatumEncoder
import io.iohk.scevm.testing.CoreDiffxInstances._
import io.iohk.scevm.trustlesssidechain._
import io.iohk.scevm.trustlesssidechain.cardano._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import DatumDecoderHelpers.decodeFromJson

class RegisterValidatorDecoderSpec extends AnyWordSpec with Matchers with DiffShouldMatcher {

  "RegisterValidatorDatum" should {
    "be decoded from json " in {
      val result = decodeFromJson[RegisterValidatorDatum[ECDSA]](json"""
          {
            "constructor": 0,
            "fields": [
              { "bytes": "2c1965904fb17fda3105294848bc796b8e7058bceaf153f604def9bad38cac53" },
              { "bytes": "0392d7b94bc6a11c335a043ee1ff326b6eacee6230d3685861cd62bce350a172e0" },
              { "bytes": "15d1061767155ec8703d44b696296aa461f0d3b495e62116ca8a1651baa71dfb61d9e0659f2967227c28321c88dae131280aafae153db37f9fbf8d53d4781a0f" },
              { "bytes": "5218317df674a1498ed42131cbca842a82813cd80244204f7b60fbf6cfc928a70fb2c36bea1fd4398b4703b68a69c17987b216158c473afb39d505d5f676655c" },
              {
                "constructor": 0,
                "fields": [
                  {
                    "constructor": 0,
                    "fields": [ { "bytes": "8c4c3bf10e0d5826eacc0e0e329c189680894bb7199b6b5db725f9c81b1981f1" } ]
                  },
                  { "int": 0 }
                ]
              }
            ]
          }
          """)

      val expected = RegisterValidatorDatum[ECDSA](
        mainchainPubKey = EdDSA.PublicKey.fromHexUnsafe(
          "2c1965904fb17fda3105294848bc796b8e7058bceaf153f604def9bad38cac53"
        ),
        sidechainPubKey = ECDSA.PublicKey.fromHexUnsafe(
          "92d7b94bc6a11c335a043ee1ff326b6eacee6230d3685861cd62bce350a172e03d86b820b8e013a21cf96f8e84a59fc96eec8e08b4e049b2bf6f6455f2606895"
        ),
        crossChainPubKey = ECDSA.PublicKey.fromHexUnsafe(
          "92d7b94bc6a11c335a043ee1ff326b6eacee6230d3685861cd62bce350a172e03d86b820b8e013a21cf96f8e84a59fc96eec8e08b4e049b2bf6f6455f2606895"
        ),
        mainchainSignature = EdDSA.Signature.fromHexUnsafe(
          "15d1061767155ec8703d44b696296aa461f0d3b495e62116ca8a1651baa71dfb61d9e0659f2967227c28321c88dae131280aafae153db37f9fbf8d53d4781a0f"
        ),
        sidechainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "5218317df674a1498ed42131cbca842a82813cd80244204f7b60fbf6cfc928a70fb2c36bea1fd4398b4703b68a69c17987b216158c473afb39d505d5f676655c"
        ),
        crossChainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "5218317df674a1498ed42131cbca842a82813cd80244204f7b60fbf6cfc928a70fb2c36bea1fd4398b4703b68a69c17987b216158c473afb39d505d5f676655c"
        ),
        consumedInput = UtxoId(
          MainchainTxHash(Hex.decodeUnsafe("8c4c3bf10e0d5826eacc0e0e329c189680894bb7199b6b5db725f9c81b1981f1")),
          0
        )
      )
      result shouldMatchTo Right(expected)
    }

    "return an error" in {
      val result = decodeFromJson[RegisterValidatorDatum[ECDSA]](json"""
        {
          "constructor": 0,
          "fields":  [
            { "bytes":  "92d7b94bc6a11c335a043ee1ff326b6eacee6230d3685861cd62bce350a172e03d86b820b8e013a21cf96f8e84a59fc96eec8e08b4e049b2bf6f6455f2606895" }
          ]
        }
        """)

      result shouldMatchTo Left(DatumDecodeError("Field number mismatch when deserializing RegisterValidatorDatum"))
    }

    "correctly serialize RegisterValidatorSignedMessage" in {
      val message =
        RegisterValidatorSignedMessage(
          sidechainParams = SidechainParams(
            ChainId(101),
            BlockHash(Hex.decodeUnsafe("9241f550ab67b74928f0fa33bdf61092f9864cb97783e8254132cadeb6989534")),
            UtxoId(
              MainchainTxHash(
                Hex.decodeUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e")
              ),
              0
            ),
            2,
            3
          ),
          sidechainPubKey = ECDSA.PublicKey
            .fromCompressedBytes(
              Hex.decodeUnsafe("0392d7b94bc6a11c335a043ee1ff326b6eacee6230d3685861cd62bce350a172e0")
            )
            .toOption
            .get,
          inputUtxo = UtxoId.parseUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0")
        )

      val serialized = ByteString(Cbor.encode(DatumEncoder[RegisterValidatorSignedMessage].encode(message)).toByteArray)
      val expected = Hex.decodeUnsafe(
        "d8799fd8799f186558209241f550ab67b74928f0fa33bdf61092f9864cb97783e8254132cadeb6989534d8799fd8799f58207247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3eff00ff0203ff58210392d7b94bc6a11c335a043ee1ff326b6eacee6230d3685861cd62bce350a172e0d8799fd8799f58207247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3eff00ffff"
      )

      ByteString(serialized) shouldMatchTo expected
    }
  }
}
