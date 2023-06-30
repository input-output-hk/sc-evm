package io.iohk.scevm.sidechain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.sidechain.BridgeContract.IncomingTransactionId
import io.iohk.scevm.trustlesssidechain.cardano.MainchainTxHash
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class IncomingTransactionIdSpec extends AnyWordSpec with Matchers {
  "should convert to and from mainchain tx hash " in {
    val txHash          = MainchainTxHash.decodeUnsafe("d5c5a624858d4f3d8e09d888cda0d6bd065889edc3b0784c0a3a02f996f2c95c")
    val txHashRecovered = IncomingTransactionId.fromTxHash(txHash).toTxHash
    txHashRecovered shouldBe Right(txHash)
  }

  "should return error if cannot convert from bytes" in {
    val bytes           = Hex.decodeAsArrayUnsafe("d5c5a624858d4f3d8e09d888cda0d6bd065889edc3b0784c0a3a02f996f2c9")
    val txHashRecovered = IncomingTransactionId(ByteString(bytes)).toTxHash
    txHashRecovered.isLeft shouldBe true
  }
}
