package io.iohk.cli

import cats.effect.{ExitCode, IO}
import com.monovore.decline.{Argument, Opts}
import io.circe.{Encoder, Printer}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Address

import java.security.SecureRandom

object GenerateKeyCommand {

  sealed trait KeyType
  object KeyType {
    // scalastyle:off
    case object ecdsa extends KeyType
    case object eddsa extends KeyType
    // scalastyle:on
  }
  implicit val keyTypeArgument: Argument[KeyType] =
    Argument.fromMap("keyType", List(KeyType.ecdsa, KeyType.eddsa).map(k => k.toString -> k).toMap)
  private val keyType = Opts
    .option[KeyType]("type", "Type of the key to generate")

  private val opts: Opts[KeyType] = Opts.subcommand("generate-keys", "Generate new set of keys") {
    keyType
  }

  final private case class Output(
      public: ByteString,
      publicCompressed: Option[ByteString],
      `private`: ByteString,
      address: Option[ByteString]
  )
  private object Output {
    implicit val encoder: Encoder[Output] = {
      implicit val byteStringEncoder: Encoder[ByteString] = Encoder[String].contramap[ByteString](Hex.toHexString)
      io.circe.generic.semiauto.deriveEncoder
    }
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map { keyType =>
      val random = new SecureRandom()
      val output = keyType match {
        case KeyType.`ecdsa` =>
          val (prv, pub) = ECDSA.generateKeyPair(random)
          val address    = Address.fromPublicKey(pub)
          Output(pub.bytes, Some(pub.compressedBytes), prv.bytes, Some(address.bytes))
        case KeyType.`eddsa` =>
          val (prv, pub) = EdDSA.generateKeyPair(random)
          Output(pub.bytes, None, prv.bytes, None)
      }
      // scalastyle:off regex
      IO.println(Printer.noSpaces.copy(dropNullValues = true).print(Encoder[Output].apply(output))).as(ExitCode.Success)
    // scalastyle:on regex
    }
}
