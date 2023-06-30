package io.iohk.ethereum.crypto

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.security.SecureRandom

class EcdsaSpec extends AnyFreeSpec with Matchers {
  "should fail to create ecdsa public key if the key length is other than 64" in {
    val result = ECDSA.PublicKey.fromBytes(
      ByteString(
        Hex
          .decodeAsArrayUnsafe(
            "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1af07b158f244cd0de2134ac7c1d371cffbfae4db40801a2572e531c573cda9b5"
          )
          .take(60)
      )
    )
    result shouldBe Left("Invalid key length. Expected 64 but got 60")
  }

  "should fail to create ecdsa public key from a compressed key if the key length is other than 33" in {
    val result = ECDSA.PublicKey.fromCompressedBytes(
      ByteString(
        Hex
          .decodeAsArrayUnsafe(
            "03fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1"
          )
          .take(30)
      )
    )
    result shouldBe Left("Invalid key length. Expected 33 but got 30")
  }

  "should fail to create ecdsa private key if the key length is other than 32" in {
    val result = ECDSA.PrivateKey.fromBytes(
      ByteString(
        Hex
          .decodeAsArrayUnsafe(
            "000000000000000000000000000000000000000000000000000000000000002a"
          )
          .take(16)
      )
    )
    result shouldBe Left("Invalid key length. Expected 32 but got 16")
  }

  "should fail to create ecdsa public key if the key length is other than 64 - from hex" in {
    val result = ECDSA.PublicKey.fromHex(
      "fe8d1eb1bcb3432b1db5833ff5f2226d9cb5e65cee430558c18ed3a3c86ce1a"
    )
    result shouldBe Left("Invalid key length. Expected 64 but got 32")
  }

  "should fail to create ecdsa private key if the key length is other than 32 - from hex" in {
    val result = ECDSA.PrivateKey.fromHex(
      "0000000000000000000000000000000000000000000000000000000000000"
    )
    result shouldBe Left("Invalid key length. Expected 32 but got 31")
  }

  "should recover public key from private key" in {
    val (prvKey, pubKey) = ECDSA.generateKeyPair(new SecureRandom())
    ECDSA.PublicKey.fromPrivateKey(prvKey) shouldBe pubKey
  }
}
