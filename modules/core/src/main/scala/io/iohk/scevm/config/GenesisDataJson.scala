package io.iohk.scevm.config

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Nonce, ObftGenesisAccount, ObftGenesisData, UInt256, _}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.json4s.JsonAST.JString
import org.json4s.native.JsonParser.parse
import org.json4s.{CustomKeySerializer, CustomSerializer, DefaultFormats, Formats, JValue}

object GenesisDataJson {

  def fromJsonString(json: String): ObftGenesisData = {
    import JsonSerializers._
    implicit val formats: Formats =
      DefaultFormats + ByteStringJsonSerializer + UInt256JsonDeserializer + Uint256KeyDeserializer
    parse(json).extract[RawObftGenesisDataFile].toObftGenesisData
  }

  /** Intermediate representation of the genesis data file without types. It is used to get a basic structure */
  final private case class RawObftGenesisDataFile(
      coinbase: String,
      gasLimit: String,
      timestamp: String,
      alloc: Map[String, ObftGenesisAccount],
      accountStartNonce: String
  ) {
    def toObftGenesisData: ObftGenesisData = ObftGenesisData(
      coinbase = Address(coinbase),
      gasLimit = Hex.parseHexNumberUnsafe(gasLimit),
      timestamp = UnixTimestamp.fromSeconds(Hex.parseHexNumberUnsafe(timestamp).toLong),
      alloc = alloc.map { case (addrString, genesisAccount) =>
        Address(addrString) -> genesisAccount
      },
      accountStartNonce = Nonce.fromHexUnsafe(accountStartNonce)
    )
  }

  private object JsonSerializers {

    def deserializeByteString(jv: JValue): ByteString = jv match {
      case JString(s) =>
        val noPrefix = s.replace("0x", "")
        val inp =
          if (noPrefix.length % 2 == 0) noPrefix
          else "0" ++ noPrefix
        Hex.decode(inp) match {
          case Right(bs) => bs
          case Left(_)   => throw new RuntimeException("Cannot parse hex string: " + s)
        }
      case other => throw new RuntimeException("Expected hex string, but got: " + other)
    }

    object ByteStringJsonSerializer
        extends CustomSerializer[ByteString](_ =>
          (
            { case jv => deserializeByteString(jv) },
            PartialFunction.empty
          )
        )

    def deserializeUint256String(jv: JValue): UInt256 = jv match {
      case JString(s) => uint256fromString(s)
      case other      => throw new RuntimeException("Expected hex string, but got: " + other)
    }

    object UInt256JsonDeserializer
        extends CustomSerializer[UInt256](_ => ({ case jv => deserializeUint256String(jv) }, PartialFunction.empty))

    object Uint256KeyDeserializer
        extends CustomKeySerializer[UInt256](_ => ({ case jv: String => uint256fromString(jv) }, PartialFunction.empty))
  }

  private def uint256fromString(s: String) =
    if (s.startsWith("0x")) {
      val noPrefix = s.replace("0x", "")
      val inp = {
        if (noPrefix.length % 2 == 0) noPrefix
        else "0" ++ noPrefix
      }
      UInt256(BigInt(inp, 16))
    } else {
      UInt256(BigInt(s))
    }
}
