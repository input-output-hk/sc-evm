package io.iohk.scevm.cardanofollower.plutus

import io.circe.parser._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.dbsync.CirceDatumAdapter._
import io.iohk.scevm.plutus._
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor2
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class PlutusDatumDecoderSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  "DatumDecoder" should {
    "decode datum examples taken from db-sync" in forAll(examples) { (json, expected) =>
      val result = decode[Datum](json).fold(throw _, identity)
      result shouldBe expected
    }
  }

  // scalastyle:off method.length
  // A few example taken from db sync on testnet
  def examples: TableFor2[String, Datum] = Table[String, Datum](
    ("json", "expected"),
    ("""{"int": 2380000}""", IntegerDatum(2380000)),
    (
      """{"bytes": "31353631633233333032356238343937356436306561643332313038396635366634376534653861"}""",
      ByteStringDatum(
        ByteString(
          Hex.decodeAsArrayUnsafe("31353631633233333032356238343937356436306561643332313038396635366634376534653861")
        )
      )
    ),
    (
      """{"list": [{"bytes": "30"}, {"bytes": "bfcca8b700712fc3f330bee764fa28d55c67bdd242fefe57b43b8eac"}]}""",
      ListDatum(
        Vector(
          ByteStringDatum(ByteString(Hex.decodeAsArrayUnsafe("30"))),
          ByteStringDatum(
            ByteString(Hex.decodeAsArrayUnsafe("bfcca8b700712fc3f330bee764fa28d55c67bdd242fefe57b43b8eac"))
          )
        )
      )
    ),
    (
      """{"map": [{"k": {"bytes": "48656c6c6f"}, "v": {"bytes": "54524f504321"}}]}""",
      MapDatum(
        Vector(
          DatumMapItem(
            ByteStringDatum(ByteString(Hex.decodeAsArrayUnsafe("48656c6c6f"))),
            ByteStringDatum(ByteString(Hex.decodeAsArrayUnsafe("54524f504321")))
          )
        )
      )
    ),
    (
      """{"fields": [{"fields": [{"bytes": "92fb9f6f3c79e78d76daf9360899cf67c1e244e9c8e160a79affb7a2"}, {"bytes": "31"}, {"int": 1}], "constructor": 0}], "constructor": 1}""",
      ConstructorDatum(
        1,
        Vector(
          ConstructorDatum(
            0,
            Vector(
              ByteStringDatum(
                ByteString(Hex.decodeAsArrayUnsafe("92fb9f6f3c79e78d76daf9360899cf67c1e244e9c8e160a79affb7a2"))
              ),
              ByteStringDatum(ByteString(Hex.decodeAsArrayUnsafe("31"))),
              IntegerDatum(1)
            )
          )
        )
      )
    )
  )
  // scalastyle:on
}
