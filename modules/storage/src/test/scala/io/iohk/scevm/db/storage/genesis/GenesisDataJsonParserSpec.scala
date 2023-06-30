package io.iohk.scevm.db.storage.genesis

import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.GenesisDataJson
import io.iohk.scevm.domain.{Address, Nonce, ObftGenesisAccount, ObftGenesisData, UInt256}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

class GenesisDataJsonParserSpec extends AnyWordSpec with Matchers {
  "should parse complex genesis file correctly" in {
    val result = Try(
      GenesisDataJson.fromJsonString(
        """{
          |  "accountStartNonce": "0",
          |  "alloc": {
          |    "0xdb8a3344c1ba480ebdfdea442f5008f76b4fc3ae": {
          |      "balance": "1606938044258990275541962092341162602522202993782792835301376"
          |    },
          |    "0x696f686b2e6d616d626100000000000000000000": {
          |      "balance": "1606938044258990275541962092341162602522202993782792835301376",
          |      "code": "6080604052600436106101355760003560e01c80638a951d781161",
          |      "storage": {
          |        "0x03": "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
          |      }
          |    },
          |    "0x30f6786ee99dcee95835a9bf834db5a352b48460": {
          |      "balance": "100000000000000000000000000000000000000000000000000000000000000000000000000"
          |    }
          |  },
          |  "coinbase": "0x0000000000000000000000000000000000000123",
          |  "gasLimit": "0x7A1200",
          |  "timestamp": "0x629ea310"
          |}
          |""".stripMargin
      )
    )
    result.get shouldBe ObftGenesisData(
      Address("0x0000000000000000000000000000000000000123"),
      BigInt("7A1200", 16),
      UnixTimestamp.fromSeconds(BigInt("629ea310", 16).longValue),
      Map(
        Address("0xdb8a3344c1ba480ebdfdea442f5008f76b4fc3ae") -> ObftGenesisAccount(
          UInt256(BigInt("1606938044258990275541962092341162602522202993782792835301376")),
          None,
          None,
          Map.empty
        ),
        Address("0x696f686b2e6d616d626100000000000000000000") -> ObftGenesisAccount(
          UInt256(BigInt("1606938044258990275541962092341162602522202993782792835301376")),
          Some(Hex.decodeUnsafe("6080604052600436106101355760003560e01c80638a951d781161")),
          None,
          Map(
            UInt256(3) -> UInt256(BigInt("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16))
          )
        ),
        Address("0x30f6786ee99dcee95835a9bf834db5a352b48460") -> ObftGenesisAccount(
          UInt256(BigInt("100000000000000000000000000000000000000000000000000000000000000000000000000")),
          None,
          None,
          Map.empty
        )
      ),
      Nonce.Zero
    )
  }
}
