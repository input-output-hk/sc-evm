package io.iohk.cli

import cats.effect.{ExitCode, IO}
import com.monovore.decline.Opts
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.Address

object DeriveAddressCommand {

  private val pubKeyBytes = Opts.argument[String]("Public key as hex")

  private val opts: Opts[String] = Opts.subcommand("derive-address", "Derive an address from a public key") {
    pubKeyBytes
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map { pubKeyBytes =>
      val pub     = ECDSA.PublicKey.fromHexUnsafe(pubKeyBytes)
      val address = Address.fromPublicKey(pub)
      // scalastyle:off regex
      IO.println(address.toString).as(ExitCode.Success)
    // scalastyle:on regex
    }
}
