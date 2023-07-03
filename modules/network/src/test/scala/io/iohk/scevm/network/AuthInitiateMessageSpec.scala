package io.iohk.scevm.network

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.utils.{ByteUtils, Hex}
import io.iohk.scevm.network.rlpx.{AuthHandshaker, AuthInitiateMessage}
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.{ECKeyGenerationParameters, ECPublicKeyParameters}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.security.SecureRandom

class AuthInitiateMessageSpec extends AnyFlatSpec with Matchers {

  "AuthInitiateMessage" should "encode and decode itself" in {
    val keyPair = {
      val generator    = new ECKeyPairGenerator
      val secureRandom = new SecureRandom()
      generator.init(new ECKeyGenerationParameters(curve, secureRandom))
      generator.generateKeyPair()
    }

    val nonce = ByteUtils.randomBytes(AuthHandshaker.NonceSize)

    val signature = ECDSASignature(BigInt("123"), BigInt("456"), 0.toByte)

    val msg = AuthInitiateMessage(
      signature,
      ByteString(Array.fill(32)(0.toByte)),
      keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ,
      ByteString(nonce),
      knownPeer = false
    )

    AuthInitiateMessage.decode(msg.encoded.toArray) shouldBe msg
  }

  it should "decode predefined message" in {
    val inputHex =
      "B50149D21491FD549AA7B60BF18B65865D078DC09A4BD10A574457FF1AB1C0193DAF513F7D48B7C2EAD41A7F10DD56B167D83E8F649A2795757CFE4965AA930E00F30BAC135324F493AEEAF4C9B48DF0F1F9A28A3DEDF9B1D85AF45A27F7B6F0546F8A80D14311C39F35F516FA664DEAAAA13E85B2F7493F37F6144D86991EC012937307647BD3B9A82ABE2974E1407241D54947BBB39763A4CAC9F77166AD92A0CAE0187FD4EB042EB7A47EEDDA185CFEB59DEA550418D1D036B76E03A5BF74AC00"

    val expectedMsg = AuthInitiateMessage(
      signature = ECDSASignature(
        r = BigInt("81870901931874412952660009205824222047471672340278145383000560930072854380569"),
        s = BigInt("27900842753040147848386185004093503271309114686926362065982995477587410195214"),
        v = 27.toByte
      ),
      ephemeralPublicHash = Hex.decodeUnsafe("F30BAC135324F493AEEAF4C9B48DF0F1F9A28A3DEDF9B1D85AF45A27F7B6F054"),
      publicKey = curve.getCurve.decodePoint(
        Hex.decodeAsArrayUnsafe(
          "046F8A80D14311C39F35F516FA664DEAAAA13E85B2F7493F37F6144D86991EC012937307647BD3B9A82ABE2974E1407241D54947BBB39763A4CAC9F77166AD92A0"
        )
      ),
      nonce = Hex.decodeUnsafe("CAE0187FD4EB042EB7A47EEDDA185CFEB59DEA550418D1D036B76E03A5BF74AC"),
      knownPeer = false
    )

    val decoded = AuthInitiateMessage.decode(Hex.decodeAsArrayUnsafe(inputHex))

    decoded shouldBe expectedMsg
  }

}
