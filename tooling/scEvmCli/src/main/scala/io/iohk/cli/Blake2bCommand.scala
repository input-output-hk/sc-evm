package io.iohk.cli

import cats.data.Validated
import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.trustlesssidechain.cardano.{Blake2bHash28, Blake2bHash32}

object Blake2bCommand {
  final private case class Params(hash: ByteString => ByteString, data: ByteString)

  private val hashLength: Opts[ByteString => ByteString] = Opts
    .option[Int]("length", "Length of the resulting hash")
    .withDefault(Blake2bHash28.BytesLength)
    .mapValidated { int =>
      if (int == Blake2bHash32.BytesLength) {
        Validated.valid(Blake2bHash32.hash(_).bytes)
      } else if (int == Blake2bHash28.BytesLength) {
        Validated.valid(Blake2bHash28.hash(_).bytes)
      } else {
        Validated.invalidNel("Invalid hash length")
      }
    }

  private val data = Opts.argument[String]().mapValidated(str => Validated.fromEither(Hex.decode(str)).toValidatedNel)

  private val opts: Opts[Params] = Opts.subcommand("blake2b", "Compute blake2b hash") {
    (hashLength, data).mapN(Params)
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map { case Params(hash, data) =>
      // scalastyle:off regex
      IO.println(Hex.toHexString(hash(data))).as(ExitCode.Success)
    // scalastyle:on regex
    }
}
