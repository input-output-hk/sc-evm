package io.iohk.ethereum.crypto

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ECDSASignatureSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks with SecureRandomBuilder {
  "ECDSASignature" should "recover public key correctly for go ethereum transaction" in {
    val bytesToSign = Hex.decodeAsArrayUnsafe("5a1465f4683bf2c18fc72c0789239c0f52b3ceac666ca9551cf265a11abe912c")
    val signatureRandom =
      ByteString(Hex.decodeAsArrayUnsafe("f3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401"))
    val signature =
      ByteString(Hex.decodeAsArrayUnsafe("53629a403579f5ce57bcbefba2616b1c6156d308ddcd37372c94943fdabeda97"))
    val pointSign = 28

    val sig =
      ECDSASignature(BigInt(1, signatureRandom.toArray[Byte]), BigInt(1, signature.toArray[Byte]), pointSign.toByte)

    sig.publicKey(bytesToSign).isEmpty shouldBe false
  }

  it should "fail on case from transaction 74c45d0cf2332cc021bebdfee6b1c1da0b58e8f4154537adb79b025f722920a4" in {
    val bytesToSign = Hex.decodeAsArrayUnsafe("2bb3925f178aa22c11435c61899e134fb7b1227016274b5f7b9d85c4469130ba")
    val signatureRandom =
      ByteString(Hex.decodeAsArrayUnsafe("fbe3df0cf030655d817a89936850d1cc00c07c35d3b21be73cfe9a730ea8b753"))
    val signature =
      ByteString(Hex.decodeAsArrayUnsafe("62d73b6a92ac23ff514315fad795bbac6d485481d356329d71467e93c87dfa42"))
    val pointSign = 0x1f

    val sig =
      ECDSASignature(BigInt(1, signatureRandom.toArray[Byte]), BigInt(1, signature.toArray[Byte]), pointSign.toByte)

    sig.publicKey(bytesToSign).isEmpty shouldBe true
  }

  it should "fail public key recover without throwing when signature is bad (Invalid point compression)" in {
    val sig = ECDSASignature(
      Hex.decodeUnsafe("149a2046f51f5d043633664d76eef4f99cdba8e53851dcda57224dfe8770f98a"),
      Hex.decodeUnsafe("a8898478e9aae9fadb71c7ab5451d47d2efa4199fc26ecc1da62ce8fb77e06f1"),
      28.toByte
    )
    val messageHash = Hex.decodeUnsafe("a1ede9cdf0b6fe37a384b265dce6b74a7464f11799dcee022f628450a19cf4eb")

    sig.publicKey(messageHash).isEmpty shouldBe true
  }

  it should "sign message and recover public key" in {
    forAll(arbitrary[Array[Byte]], Arbitrary.arbitrary[Unit].map(_ => generateKeyPair(secureRandom))) {
      (message, keys) =>
        val pubKey = keys.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
        val msg    = kec256(message)

        val signature = ECDSASignature.sign(msg, keys)
        val recPubKey = signature.publicKey(msg)

        val result = recPubKey
          .map(a => ECDSASignature.UncompressedIndicator +: a)
          .map(curve.getCurve.decodePoint)
          .map(_.getEncoded(true))
          .map(ByteString(_))
        val expected = Some(pubKey.getEncoded(true)).map(ByteString(_))

        result shouldBe expected
    }
  }
}
