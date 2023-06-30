package io.iohk.scevm.domain

import io.iohk.ethereum.rlp
import io.iohk.ethereum.rlp.{RLPEncoder, RLPValue}
import io.iohk.ethereum.utils.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NonceSpec extends AnyFlatSpec with Matchers {
  import rlp.RLPImplicits.bigIntEncDec
  val encoder: RLPEncoder[Nonce] = implicitly

  "RLP encoding of Nonce" should "encode Nonce(0)" in {
    val nonce    = Nonce(BigInt(0))
    val expected = RLPValue(Hex.decodeAsArrayUnsafe(""))

    rlp.encode(encoder.encode(nonce)) shouldBe rlp.encode(expected)
  }

  it should "encode Nonce(12345)" in {
    val nonce    = Nonce(BigInt(12345))
    val expected = RLPValue(Hex.decodeAsArrayUnsafe("3039"))

    rlp.encode(encoder.encode(nonce)) shouldBe rlp.encode(expected)
  }
}
