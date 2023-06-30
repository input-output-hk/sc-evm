package io.iohk.cli

import cats.effect.{ExitCode, IO}
import cats.implicits._
import com.monovore.decline.Opts
import io.circe.Json
import io.circe.syntax.EncoderOps
import io.iohk.jsonRpcClient.JsonRpcClient
import io.iohk.scevm.domain.Address
import sttp.model.Uri

object RequestFunds {
  final case class Params(scEvmUrl: Uri, recipient: Address, verbose: Boolean)

  val FaucetUrlEnvVar = "SC_EVM_FAUCET_URL"

  private val opts = Opts.subcommand(
    "request-funds",
    "Sends funds to given sidechain address. Amount is constant and isn't a parameter of this command."
  ) {
    val faucetUrl = CommonOpts.uriOpt(
      long = "sc-evm-url",
      help = show"The URL of faucet node.\nCan be set by $FaucetUrlEnvVar environment variable.",
      envVar = FaucetUrlEnvVar
    )
    (faucetUrl, CommonOpts.recipient, CommonOpts.verbose)
      .mapN(Params)
  }
  def apply(): Opts[IO[ExitCode]] = opts.map { case Params(scEvmUrl, recipient, verbose) =>
    val console = cats.effect.std.Console.make[IO]
    JsonRpcClient.asResource[IO](scEvmUrl, verbose).use { implicit rpcClient =>
      rpcClient
        .callEndpoint[Json]("faucet_sendFunds", List(recipient.toUnprefixedString.asJson))
        .flatMap(json => console.print(show"$json\n"))
        .as(ExitCode.Success)
        .recoverWith {
          case ex: Exception if ex.getMessage.contains("Method not found") =>
            console
              .errorln(
                show"Endpoint faucet_sendFunds not found. Verify that node ${scEvmUrl.toString()} is a faucet node."
              )
              .as(ExitCode.Error)
        }
    }
  }

}
