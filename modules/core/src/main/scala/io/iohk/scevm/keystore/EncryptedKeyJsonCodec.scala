package io.iohk.scevm.keystore

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Address
import io.iohk.scevm.keystore.EncryptedKey.{KdfParams, Pbkdf2Params, ScryptParams, _}
import org.json4s.JsonAST.{JObject, JString, JValue}
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats, JField}

import java.util.UUID

object EncryptedKeyJsonCodec {

  private val byteStringSerializer = new CustomSerializer[ByteString](_ =>
    (
      { case JString(s) => Hex.decodeUnsafe(s) },
      { case bs: ByteString => JString(Hex.toHexString(bs.toArray)) }
    )
  )

  implicit private val formats: Formats = DefaultFormats + byteStringSerializer

  private def asHex(bs: ByteString): String =
    Hex.toHexString(bs.toArray)

  def toJson(encKey: EncryptedKey): String = {
    import encKey._
    import cryptoSpec._

    val json =
      ("id"        -> id.toString) ~
        ("address" -> asHex(address.bytes)) ~
        ("version" -> version) ~
        ("crypto" -> (
          ("cipher"         -> cipher) ~
            ("ciphertext"   -> asHex(ciphertext)) ~
            ("cipherparams" -> ("iv" -> asHex(iv))) ~
            encodeKdf(kdfParams) ~
            ("mac" -> asHex(mac))
        ))

    pretty(render(json))
  }

  def fromJson(jsonStr: String): Either[String, EncryptedKey] = {
    val json = parse(jsonStr).transformField { case JField(k, v) => JField(k.toLowerCase, v) }

    val uuid    = UUID.fromString((json \ "id").extract[String])
    val address = Address((json \ "address").extract[String])
    val version = (json \ "version").extract[Int]

    val crypto     = json \ "crypto"
    val cipher     = (crypto \ "cipher").extract[String]
    val ciphertext = (crypto \ "ciphertext").extract[ByteString]
    val iv         = (crypto \ "cipherparams" \ "iv").extract[ByteString]
    val mac        = (crypto \ "mac").extract[ByteString]

    for {
      kdfParams <- extractKdf(crypto)
      cryptoSpec = CryptoSpec(cipher, ciphertext, iv, kdfParams, mac)
    } yield EncryptedKey(uuid, address, cryptoSpec, version)
  }

  private def encodeKdf(kdfParams: KdfParams): JObject =
    kdfParams match {
      case ScryptParams(_, _, _, _, _) =>
        ("kdf"         -> Scrypt) ~
          ("kdfparams" -> Extraction.decompose(kdfParams))

      case Pbkdf2Params(_, _, _, _) =>
        ("kdf"         -> Pbkdf2) ~
          ("kdfparams" -> Extraction.decompose(kdfParams))
    }

  private def extractKdf(crypto: JValue): Either[String, KdfParams] = {
    val kdf = (crypto \ "kdf").extract[String]
    kdf.toLowerCase match {
      case Scrypt =>
        Right((crypto \ "kdfparams").extract[ScryptParams])

      case Pbkdf2 =>
        Right((crypto \ "kdfparams").extract[Pbkdf2Params])

      case _ => Left("Error extracting Kdf, Scrypt or Pbkdf2 expected")
    }
  }

}
