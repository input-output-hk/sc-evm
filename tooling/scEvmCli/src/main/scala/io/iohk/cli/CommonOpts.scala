package io.iohk.cli

import cats.data.Validated
import cats.implicits.showInterpolator
import com.monovore.decline.Opts
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, BlockNumber}
import sttp.model.Uri

import scala.util.Properties

object CommonOpts {
  val NodeUrlEnvVar = "SC_EVM_NODE_URL"
  def nodeUrl(configDefault: Option[Uri]): Opts[Uri] = {
    val opt = uriOpt(
      long = "sc-evm-url",
      help = "The URL of sidechain node serving RPC endpoints.\n" +
        show"Can be set by $NodeUrlEnvVar environment variable.\n" +
        "Can be read from config.json file if present.",
      envVar = NodeUrlEnvVar
    )
    configDefault.fold(opt)(opt.withDefault)
  }
  val recipient: Opts[Address] = CommonOpts.addressOpt("recipient", "Recipient address on the sidechain")
  val recipientFilter: Opts[Option[Address]] =
    CommonOpts.addressOpt("recipient", "Optional filter for the recipient address").orNone

  def signingKeyFile(configDefault: Option[String]): Opts[String] = {
    val opt = Opts.option[String](
      "signing-key-file",
      "Path to signing key.\n" +
        "Can be read from config.json file if present."
    )
    configDefault.fold(opt)(opt.withDefault)
  }

  val CtlExecutableEnvVar = "CTL_EXECUTABLE"
  val ctlExecutable: Opts[List[String]] = {
    val opt = Opts.option[String](
      "ctl-executable",
      "Path to trustless-sidechain-ctl executable.\n" +
        show"Can be set by $CtlExecutableEnvVar environment variable."
    )
    Properties.envOrNone(CtlExecutableEnvVar).fold(opt)(opt.withDefault)
  }.mapValidated { str =>
    val split = str.split("\\s+").toList
    Validated.condNel(split.nonEmpty, split, "ctl-executable empty")
  }

  def addressOpt(long: String, help: String): Opts[Address] =
    Opts
      .option[String](long, help)
      .mapValidated(addrStr => Validated.fromEither(Hex.decode(addrStr)).toValidatedNel)
      .mapValidated(addrHex => Validated.fromEither(Address.fromBytes(addrHex)).toValidatedNel)

  def uriOpt(long: String, help: String, envVar: String): Opts[Uri] = {
    val envValue = Properties.envOrNone(envVar)
    val opt      = Opts.option[String](long, help)
    envValue
      .fold(opt)(opt.withDefault)
      .mapValidated(uriStr => Validated.fromEither(Uri.parse(uriStr)).toValidatedNel)
  }
  def blockNumberOpt(long: String, help: String): Opts[BlockNumber] =
    Opts.option[BigInt](long, help).map(BlockNumber.apply)

  val verbose: Opts[Boolean] = Opts.flag("verbose", "Display additional information").orFalse
}
