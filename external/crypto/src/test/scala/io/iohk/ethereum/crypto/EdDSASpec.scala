package io.iohk.ethereum.crypto

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.{
  Ed25519KeyGenerationParameters,
  Ed25519PrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertions, EitherValues}

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class EdDSASpec extends AnyFreeSpec with Matchers with SecureRandomBuilder with EitherValues {
  "valid signature should be valid" in {
    val keyPairGenerator = new Ed25519KeyPairGenerator
    keyPairGenerator.init(new Ed25519KeyGenerationParameters(secureRandom))
    val asymmetricCipherKeyPair = keyPairGenerator.generateKeyPair
    val privateKey              = asymmetricCipherKeyPair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters]
    val publicKeyEncoded        = asymmetricCipherKeyPair.getPublic.asInstanceOf[Ed25519PublicKeyParameters].getEncoded
    val message                 = "Message to sign".getBytes(StandardCharsets.UTF_8)

    // create the signature
    val signer = new Ed25519Signer
    signer.init(true, privateKey)
    signer.update(message, 0, message.length)
    // verify the signature
    val publicKey = EdDSA.PublicKey.fromBytesUnsafe(ByteString(publicKeyEncoded))
    val signature = EdDSA.Signature.fromBytes(ByteString(signer.generateSignature))
    signature.value.verify(ByteString(message), publicKey) shouldBe true
  }

  "should not parse signature with incorrect length" in {
    val tooShortSignature =
      "358ed62250f9e432a67da5cca836e477a77db3c555357930fb5c00d0cc002ae91369718ceba03db54d287d89d5773e77e8235bdc936290be9e63aaecbc002"
    EdDSA.Signature.fromBytes(ByteString(Hex.decodeAsArrayUnsafe(tooShortSignature))) match {
      case Left(_)      => // OK
      case Right(value) => Assertions.fail(s"Expected left but got $value")
    }

    val tooLongSignature =
      "358ed62250f9e432a67da5cca836e477a77db3c555357930fb5c00d0cc002ae91369718ceba03db54d287d89d5773e77e8235bdc936290be9e63aaecbc002f0c0c"

    EdDSA.Signature.fromBytes(ByteString(Hex.decodeAsArrayUnsafe(tooLongSignature))) match {
      case Left(_)      => // OK
      case Right(value) => Assertions.fail(s"Expected left but got $value")
    }
  }

  "signature with wrong message should be invalid" in {
    val pubKey = EdDSA.PublicKey.fromBytesUnsafe(
      ByteString(Hex.decodeAsArrayUnsafe("c115c2b35fd2c8faa9bb12bcad8f393bdc08343e9ed71528e2cce76c26d77658"))
    )
    val signature = EdDSA.Signature.fromBytes(
      ByteString(
        Hex.decodeAsArrayUnsafe(
          "2fbb950b0a8af8ae3706dd62478b6406699aac50900d28ebf5aaf3ecaa54286e9316aec0b1751540ab44f1d86e7041bc5c6813972b99bfa375e36aee3c1fde0c"
        )
      )
    )
    val invalidMessage = ByteString("Message to sign 22".getBytes(StandardCharsets.UTF_8))
    signature.value.verify(invalidMessage, pubKey) shouldBe false
  }

  "valid signature should be valid after deserialization" in {
    val pubKey = EdDSA.PublicKey.fromBytesUnsafe(
      ByteString(Hex.decodeAsArrayUnsafe("c115c2b35fd2c8faa9bb12bcad8f393bdc08343e9ed71528e2cce76c26d77658"))
    )
    val signature = EdDSA.Signature.fromBytes(
      ByteString(
        Hex.decodeAsArrayUnsafe(
          "2fbb950b0a8af8ae3706dd62478b6406699aac50900d28ebf5aaf3ecaa54286e9316aec0b1751540ab44f1d86e7041bc5c6813972b99bfa375e36aee3c1fde0c"
        )
      )
    )
    val originalMessage = ByteString("Message to sign".getBytes(StandardCharsets.UTF_8))
    signature.value.verify(originalMessage, pubKey) shouldBe true
  }

  "signature with wrong pubKey should be invalid" in {
    val pubKey = EdDSA.PublicKey.fromBytesUnsafe(
      ByteString(Hex.decodeAsArrayUnsafe("c115c2b35fd2c8faa9bb12bcad8f393bdc08343e9ed71528e2cce76c26d77646"))
    )
    val signature = EdDSA.Signature.fromBytes(
      ByteString(
        Hex.decodeAsArrayUnsafe(
          "2fbb950b0a8af8ae3706dd62478b6406699aac50900d28ebf5aaf3ecaa54286e9316aec0b1751540ab44f1d86e7041bc5c6813972b99bfa375e36aee3c1fde0c"
        )
      )
    )
    val invalidMessage = ByteString("Message to sign".getBytes(StandardCharsets.UTF_8))
    signature.value.verify(invalidMessage, pubKey) shouldBe false
  }

  "should fail to create pubKey if the key length is other than 32" in {
    val result = EdDSA.PublicKey.fromBytes(
      ByteString(
        Hex
          .decodeAsArrayUnsafe(
            "7b8bf348c19f9694cb7ea8016b824005ba89ab64a96cc75d7ec0a9f020b68543"
          )
          .take(30)
      )
    )
    result shouldBe Left("Invalid key length. Expected 32 but got 30")
  }

  "should fail to create privateKey if the key length is other than 32" in {
    val result = EdDSA.PrivateKey.fromBytes(
      ByteString(
        Hex
          .decodeAsArrayUnsafe(
            "e3e08a81e829934de06b09fc962e40b63b61a46ed83147ffa645d12053a3b118e3e08a81e829934de06b09fc962e40b63b61a46ed83147ffa645d12053a3b118"
          )
      )
    )
    result shouldBe Left("Invalid key length. Expected 32 but got 64")
  }

  "should recover public key from private" in {
    val (prvKey, pubKey) = EdDSA.generateKeyPair(new SecureRandom())
    EdDSA.PublicKey.fromPrivate(prvKey) shouldBe pubKey
  }
}
