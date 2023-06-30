package io.iohk.cli

import cats.effect.{ExitCode, IO}
import com.monovore.decline._
import com.monovore.decline.effect.CommandIOApp

import scala.util.Properties

object CliLauncher extends CommandIOApp(name = "sc-evm-cli", header = "EVM Sidechain CLI") {

  override def main: Opts[IO[ExitCode]] = {
    val configFile                  = Properties.envOrNone("SC_EVM_CLI_CONFIG").getOrElse("config.json")
    lazy val maybeConfig            = CliConfig.load(configFile)
    def configDefaultUrl            = maybeConfig.flatMap(_.runtimeConfig.scEvm).flatMap(_.getUri)
    def configDefaultSigningKeyFile = maybeConfig.flatMap(_.paymentSigningKeyFile)

    Blake2bCommand()
      .orElse(BurnFuel(configDefaultUrl, configDefaultSigningKeyFile))
      .orElse(ClaimFuel(configDefaultUrl, configDefaultSigningKeyFile))
      .orElse(CompressEcdsaKeyCommand())
      .orElse(CreateLockTxCommand())
      .orElse(DeriveAddressCommand())
      .orElse(DeriveKeyCommand())
      .orElse(GenerateEpochSignaturesCommand())
      .orElse(GenerateKeyCommand())
      .orElse(GenerateSignatureCommand())
      .orElse(GenesisHashCommand())
      .orElse(GetChainParams())
      .orElse(GetPendingTransactions(configDefaultUrl))
      .orElse(InspectMerkleProofCommand())
      .orElse(LockFuel(configDefaultUrl))
      .orElse(RequestFunds())
      .orElse(SearchIncomingTransactions(configDefaultUrl))
  }
}
