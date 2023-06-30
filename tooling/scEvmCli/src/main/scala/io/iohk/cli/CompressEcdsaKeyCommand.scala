package io.iohk.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.ethereum.crypto.ECDSA

object CompressEcdsaKeyCommand {

  private val pubKeyBytes = Opts
    .argument[String]("Public key as hex")
    .mapValidated(str => ECDSA.PublicKey.fromHex(str).toValidatedNel)

  private val opts: Opts[ECDSA.PublicKey] =
    Opts.subcommand("compress-ecdsa", "Convert ecdsa public key to its compressed version") {
      pubKeyBytes
    }

  def apply(): Opts[IO[ExitCode]] =
    opts.map { pubKey =>
      // scalastyle:off regex
      IO.println(show"${pubKey.compressedBytes}").as(ExitCode.Success)
      // scalastyle:on regex
    }
}
