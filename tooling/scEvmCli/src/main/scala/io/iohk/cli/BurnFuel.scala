package io.iohk.cli

import cats.data.EitherT
import cats.effect.{ExitCode, IO, std}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.jsonRpcClient.JsonRpcClient
import io.iohk.scevm.domain.Address
import sttp.model.Uri

object BurnFuel {
  final case class Params(
      scEvmUrl: Uri,
      recipient: Address,
      signingKeyFile: String,
      amount: Long,
      ctlExecutable: List[String],
      verbose: Boolean
  )

  private def opts(
      configDefaultUrl: Option[Uri],
      configDefaultSigningKeyFile: Option[String]
  ) = {
    val amount = Opts.option[Long]("amount", "The amount of tokens to burn.")

    Opts.subcommand("burn-sc-token", "Burns sidechain tokens to make transaction from main chain to sidechain") {
      (
        CommonOpts.nodeUrl(configDefaultUrl),
        CommonOpts.recipient,
        CommonOpts.signingKeyFile(configDefaultSigningKeyFile),
        amount,
        CommonOpts.ctlExecutable,
        CommonOpts.verbose
      )
        .mapN(Params)
    }
  }

  def apply(
      configDefaultUrl: => Option[Uri],
      configDefaultSigningKeyFile: => Option[String]
  ): Opts[IO[ExitCode]] = opts(configDefaultUrl, configDefaultSigningKeyFile).map {
    case Params(scEvmUrl, recipient, signingKeyFile, amount, ctlExecutable, verbose) =>
      JsonRpcClient.asResource[IO](scEvmUrl, verbose).use { implicit rpcClient =>
        (for {
          ctlExecutable   <- CtlExecutable.apply(ctlExecutable)
          sidechainParams <- EitherT.liftF(getSidechainParams)
          result <- ctlExecutable.call(
                      List(
                        "burn",
                        "--payment-signing-key-file",
                        signingKeyFile,
                        "--amount",
                        amount.toString,
                        "--recipient",
                        recipient.toString
                      ) ++ sidechainParamsToCtlParams(sidechainParams)
                    )
        } yield result).value
          .flatMap {
            case Left(error) => std.Console[IO].errorln(error).as(ExitCode.Error)
            //scalastyle:off regex
            case Right(result) => std.Console[IO].println(result).as(ExitCode.Success)
            //scalastyle:on
          }
      }

  }
}
