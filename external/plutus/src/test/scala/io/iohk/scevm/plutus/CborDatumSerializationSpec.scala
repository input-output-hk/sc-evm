package io.iohk.scevm.plutus

import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CborDatumSerializationSpec extends AnyWordSpec with Matchers {

  "cbor serialization" should {
    // This test is using this example:
    //  msgBlockProducerRegistrationMsg {
    //    bprmSidechainParams = SidechainParams {
    //      chainId = 42,
    //      genesisHash = 0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b
    //    },
    //    bprmSidechainPubKey = b39464cf49a65f10b8c3c98aa473b031f929f81d89df7319e7cb3b5c7019d463,
    //    bprmInputUtxo = TxOutRef {
    //      txOutRefId = bf15a126a06b81fda8a87d27331376b816886cc254a2c1345122a5f383c1fcf3,
    //      txOutRefIdx = 0
    //    }
    //  }

    "return the right output" in {
      val data = ConstructorDatum(
        0,
        Vector(
          ConstructorDatum(
            0,
            Vector(
              IntegerDatum(42),
              ByteStringDatum(
                Hex.decodeUnsafe("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
              )
            )
          ),
          ByteStringDatum(Hex.decodeUnsafe("b39464cf49a65f10b8c3c98aa473b031f929f81d89df7319e7cb3b5c7019d463")),
          ConstructorDatum(
            0,
            Vector(
              ConstructorDatum(
                0,
                Vector(
                  ByteStringDatum(
                    Hex.decodeUnsafe("bf15a126a06b81fda8a87d27331376b816886cc254a2c1345122a5f383c1fcf3")
                  )
                )
              ),
              IntegerDatum(0)
            )
          )
        )
      )

      val expected = Hex.decodeUnsafe(
        "d8799fd8799f182a58200b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0bff5820b39464cf49a65f10b8c3c98aa473b031f929f81d89df7319e7cb3b5c7019d463d8799fd8799f5820bf15a126a06b81fda8a87d27331376b816886cc254a2c1345122a5f383c1fcf3ff00ffff"
      )
      ByteString(Cbor.encode(data).toByteArray) shouldBe expected
    }
  }
}
