package io.iohk.cli

import cats.data.EitherT
import cats.effect.{ExitCode, IO, std}
import cats.implicits._
import com.monovore.decline.Opts
import io.iohk.jsonRpcClient.JsonRpcClient
import sttp.model.Uri

object ClaimFuel {
  final case class Params(
      scEvmUrl: Uri,
      signingKeyFile: String,
      combinedProof: String,
      ctlExecutable: List[String],
      verbose: Boolean
  )

  private def opts(
      configDefaultUrl: Option[Uri],
      configDefaultSigningKeyFile: Option[String]
  ) =
    Opts.subcommand(
      "claim-sc-token",
      "Claim sidechain tokens, that have been sent from sidechain to main chain, using obtained merkle proof"
    ) {
      val combinedProof = Opts.option[String](
        "combined-proof",
        "Merkle proof obtained using sidechain_getOutgoingTxMerkleProof rpc endpoint"
      )

      (
        CommonOpts.nodeUrl(configDefaultUrl),
        CommonOpts.signingKeyFile(configDefaultSigningKeyFile),
        combinedProof,
        CommonOpts.ctlExecutable,
        CommonOpts.verbose
      )
        .mapN(Params)
    }

  def apply(
      configDefaultUrl: => Option[Uri],
      configDefaultSigningKeyFile: => Option[String]
  ): Opts[IO[ExitCode]] = opts(configDefaultUrl, configDefaultSigningKeyFile).map {
    case Params(scEvmUrl, signingKeyFile, combinedProof, ctlExecutable, verbose) =>
      JsonRpcClient.asResource[IO](scEvmUrl, verbose).use { implicit rpcClient =>
        (for {
          ctlExecutable   <- CtlExecutable.apply(ctlExecutable)
          sidechainParams <- EitherT.liftF(getSidechainParams)
          result <- ctlExecutable.call(
                      List(
                        "claim",
                        "--payment-signing-key-file",
                        signingKeyFile,
                        "--combined-proof",
                        combinedProof
                      ) ++ sidechainParamsToCtlParams(sidechainParams)
                    )
        } yield result).value
          .flatMap {
            case Left(error) => std.Console[IO].errorln(error).as(ExitCode.Error)
            // scalastyle:off regex
            case Right(result) => std.Console[IO].print(result).as(ExitCode.Success)
            //scalastyle:on
          }
      }
  }
}
