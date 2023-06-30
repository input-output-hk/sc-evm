package io.iohk.scevm.network.rlpx

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.testing.CryptoGenerators.{asymmetricCipherKeyPairGen, fakeSignatureGen}
import io.iohk.scevm.testing.Generators.{intGen, randomSizeByteStringGen}
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class AuthInitiateMessageV4Spec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with EitherValues {

  "AuthInitiateMessageV4" when {
    "serializing" should {
      import AuthInitiateMessageV4._

      val preDefinedPubKey = {
        val privateKey =
          ECDSA.PrivateKey.fromHex("f46bf49093d585f2ea781a0bf6d83468919f547999ad91c9210256979d88eea2").value
        // pubKey as ECPoint
        ECDSA.PublicKey.fromPrivateKey(privateKey).key.getQ
      }

      val preDefinedInput = AuthInitiateMessageV4(
        ECDSASignature(BigInt("123"), BigInt("456"), 0.toByte),
        preDefinedPubKey,
        ByteString.fromInts(111, 222, 333),
        1
      )
      val preDefinedEncoded = Hex.decodeUnsafe(
        "f88ab841000000000000000000000000000000000000000000000000000000000000007b00000000000000000000000000000000000000000000000000000000000001c8e5b8409352518b11573a3b55454872b4e2e6ee6c356ff5aeceed838f61f4d247e4f1335cfddaeee2891fc02a389aa18404f37cf599bf8a51839d0e299e766504c1c400836fde4d01"
      )

      "encode to predefined message" in {
        val encoded = preDefinedInput.toBytes
        encoded shouldBe preDefinedEncoded.toArray
      }

      "decode predefined message" in {
        val decoded = preDefinedEncoded.toArray.toAuthInitiateMessageV4
        decoded shouldBe preDefinedInput
      }

      "encode-decode itself" in {
        forAll(fakeSignatureGen, randomSizeByteStringGen(0, 100), intGen, asymmetricCipherKeyPairGen) {
          case (signature, nonce, version, keyPair) =>
            val input = {
              val pubKey = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ

              AuthInitiateMessageV4(signature, pubKey, nonce, version)
            }

            val encoded = input.toBytes
            val decoded = encoded.toAuthInitiateMessageV4

            input shouldBe decoded
        }
      }
    }
  }
}
