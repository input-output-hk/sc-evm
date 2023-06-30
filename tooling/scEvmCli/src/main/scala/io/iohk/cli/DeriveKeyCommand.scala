package io.iohk.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.{Argument, Opts}
import io.circe.generic.semiauto
import io.circe.{Encoder, Printer}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Address

object DeriveKeyCommand {

  final private case class Params(prvKeyHexString: String, keyType: KeyType)

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
    .option[KeyType]("type", "Type of the key")
  private val prvKeyBytes = Opts
    .argument[String]("Private key as hex")

  private val opts: Opts[Params] = Opts.subcommand("derive-key", "Derive a public key from a private one") {
    (prvKeyBytes, keyType).mapN(Params)
  }

  final private case class EddsaOutput(public: ByteString)
  final private case class EcdsaOutput(public: ByteString, publicCompressed: ByteString, address: ByteString)

  implicit private val byteStringEncoder: Encoder[ByteString] = Encoder[String].contramap[ByteString](Hex.toHexString)

  def apply(): Opts[IO[ExitCode]] =
    opts.map { case Params(prvKeyBytes, keyType) =>
      val output = keyType match {
        case KeyType.`ecdsa` =>
          val ecdsaOutputEncoder = semiauto.deriveEncoder[EcdsaOutput]
          val prv                = ECDSA.PrivateKey.fromHexUnsafe(prvKeyBytes)
          val pub                = ECDSA.PublicKey.fromPrivateKey(prv)
          val address            = Address.fromPublicKey(pub)
          ecdsaOutputEncoder.apply(EcdsaOutput(pub.bytes, pub.compressedBytes, address.bytes))
        case KeyType.`eddsa` =>
          val eddsaOutputEncoder = semiauto.deriveEncoder[EddsaOutput]
          val prv                = EdDSA.PrivateKey.fromHexUnsafe(prvKeyBytes)
          val pub                = EdDSA.PublicKey.fromPrivate(prv)
          eddsaOutputEncoder.apply(EddsaOutput(pub.bytes))
      }
      // scalastyle:off regex
      IO.println(Printer.noSpaces.print(output)).as(ExitCode.Success)
    // scalastyle:on regex
    }
}
