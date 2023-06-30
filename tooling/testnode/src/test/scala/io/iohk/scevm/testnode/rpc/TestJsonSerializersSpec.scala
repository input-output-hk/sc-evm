package io.iohk.scevm.testnode.rpc

import io.iohk.armadillo.JsonRpcCodec
import io.iohk.armadillo.json.json4s.jsonRpcCodec
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.testnode.TestJRpcController.AccountsInRangeResponse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TestJsonSerializersSpec extends AnyWordSpec with Matchers {
  "TestJsonSerializers" should {
    "provide serialization for AccountsInRangeResponse" in {
      val codec: JsonRpcCodec[AccountsInRangeResponse] = {
        import TestJsonSerializers.formats
        import io.iohk.scevm.testnode.rpc.TestRpcSchemas.schemaForAccountsInRangeResponse
        import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits.jsonSupport
        jsonRpcCodec
      }

      val input = AccountsInRangeResponse(
        addressMap = Map[ByteString, ByteString](
          Hex.decodeUnsafe("abcd") -> Hex.decodeUnsafe("dcba"),
          Hex.decodeUnsafe("cafe") -> Hex.decodeUnsafe("abba")
        ),
        nextKey = Hex.decodeUnsafe("baba")
      )

      codec.encode(input) // does not throw
    }
  }
}
