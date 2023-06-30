package io.iohk.scevm.network.rlpx

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.rlp
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.testing.CryptoGenerators.asymmetricCipherKeyPairGen
import io.iohk.scevm.testing.Generators.{intGen, randomSizeByteStringGen}
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class AuthResponseMessageV4Spec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with EitherValues {

  "AuthResponseMessageV4" when {
    "serializing" should {
      import AuthResponseMessageV4.rlpEncDec

      val preDefinedPubKey = {
        val privateKey =
          ECDSA.PrivateKey.fromHex("f46bf49093d585f2ea781a0bf6d83468919f547999ad91c9210256979d88eea2").value
        // pubKey as ECPoint
        ECDSA.PublicKey.fromPrivateKey(privateKey).key.getQ
      }

      val preDefinedInput = AuthResponseMessageV4(preDefinedPubKey, ByteString.fromInts(111, 222, 333), 1)
      val preDefinedEncoded = Hex.decodeUnsafe(
        "f847b8409352518b11573a3b55454872b4e2e6ee6c356ff5aeceed838f61f4d247e4f1335cfddaeee2891fc02a389aa18404f37cf599bf8a51839d0e299e766504c1c400836fde4d01"
      )

      "encode to predefined message" in {
        val encoded = rlp.encode(preDefinedInput)
        encoded shouldBe preDefinedEncoded.toArray
      }

      "decode predefined message" in {
        val decoded = rlp.decode(preDefinedEncoded.toArray)
        decoded shouldBe preDefinedInput
      }

      "encode-decode itself" in {
        forAll(randomSizeByteStringGen(0, 100), intGen, asymmetricCipherKeyPairGen) { case (nonce, version, keyPair) =>
          val input = {
            val pubKey = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ

            AuthResponseMessageV4(pubKey, nonce, version)
          }

          val encoded = rlp.encode(input)
          val decoded = rlp.decode(encoded)

          input shouldBe decoded
        }
      }
    }
  }
}
