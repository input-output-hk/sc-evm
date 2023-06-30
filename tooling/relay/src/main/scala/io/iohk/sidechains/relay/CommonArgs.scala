package io.iohk.sidechains.relay

import cats.data.{NonEmptyList, Validated}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignatureNoRecovery}
import io.iohk.ethereum.utils.Hex
import sttp.model.Uri

object CommonArgs {
  val nodeUrl: Opts[Uri] = Opts
    .option[String]("node-url", "URL of sidechain node")
    .mapValidated(uri => Validated.fromEither(Uri.parse(uri)).toValidatedNel)

  val signingKeyPath: Opts[String] = Opts
    .option[String]("signing-key-path", "Path to signing key file used to invoke save-root and committee-hash")

  val signaturesArg: Opts[NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)]] =
    Opts
      .arguments[String]("list of publickey:signature")
      .mapValidated(strs => strs.traverse(parseSig).toValidatedNel)

  private def parseSig(str: String): Either[String, (ECDSA.PublicKey, ECDSASignatureNoRecovery)] =
    str.split(':') match {
      case Array(key, sig) =>
        for {
          parsedKey <- Hex.decode(key).flatMap(ECDSA.PublicKey.fromCompressedBytes)
          parsedSig <- ECDSASignatureNoRecovery.fromHex(sig)
        } yield (parsedKey, parsedSig)
      case _ => Left(s"could not parse signature $str")
    }

}
