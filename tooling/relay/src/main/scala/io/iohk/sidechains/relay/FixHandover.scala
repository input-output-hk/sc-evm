package io.iohk.sidechains.relay

import cats.data.{EitherT, NonEmptyList}
import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignatureNoRecovery}
import io.iohk.sidechains.relay.Relay.log
import io.iohk.sidechains.relay.RpcClient.RpcClientOps._
import io.iohk.sidechains.relay.RpcFormats.{CommitteeResponse, EpochToUpload, GetSignaturesResponse}
import sttp.model.Uri

class FixHandover(rpcClient: RpcClient, signingKeyPath: String) {
  def run(signatures: NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)]): IO[ExitCode] = {
    val task: IO[ExitCode] = (for {
      epochToFix <- EitherT[IO, String, EpochToUpload](rpcClient.getNextEpoch)
      _          <- EitherT.liftF[IO, String, Unit](log.info(s"Will fix epoch ${epochToFix.epoch}"))
      signaturesFromNode <-
        EitherT.liftF[IO, String, GetSignaturesResponse](
          rpcClient.getEpochSignatures(epochToFix.epoch)
        )
      committeeResponse <-
        EitherT.liftF[IO, String, CommitteeResponse](
          rpcClient.getCommittee(epochToFix.epoch)
        )
      command = buildCommand(epochToFix, signatures, signaturesFromNode, committeeResponse)
      _      <- EitherT.liftF[IO, String, String](ProcessIO.execute(command))
    } yield ExitCode.Success)
      .foldF(reason => log.error(s"fixup failed: $reason").as(ExitCode.Error), _.pure[IO])

    task.attempt.flatMap {
      case Left(exception) =>
        (log.error(s"fixup failed: $exception") >> IO(exception.printStackTrace())).as(ExitCode.Error)
      case Right(other) => IO.pure(other)
    }
  }

  private def buildCommand(
      epochToFix: EpochToUpload,
      signaturesFromCommand: NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)],
      signaturesFromNode: GetSignaturesResponse,
      committeeResponse: CommitteeResponse
  ) = {
    val previousCommittee = committeeResponse.parseCommittee
    val signatures = signaturesFromCommand
      .filter { case (pubKey, _) => previousCommittee.contains(pubKey) }
    val nextCommittee = signaturesFromNode.committeeHandover.nextCommitteePubKeys.map(ECDSA.PublicKey.fromHexUnsafe)
    EpochCommands.committeeHash(
      epochToFix.epoch,
      signaturesFromNode.params,
      previousCommittee,
      nextCommittee,
      signatures,
      signaturesFromNode.committeeHandover.previousMerkleRootHash,
      signingKeyPath
    )
  }
}

object FixHandover {

  final private case class Params(
      nodeUrl: Uri,
      signingKeyPath: String,
      signatures: NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)]
  )

  private lazy val opts: Opts[Params] =
    Opts.subcommand("fix-handover", """Send a "fix" handover by manually providing the signatures""")(
      (CommonArgs.nodeUrl, CommonArgs.signingKeyPath, CommonArgs.signaturesArg).mapN(Params.apply)
    )

  def apply(): Opts[IO[ExitCode]] =
    opts.map { case Params(nodeUrl, signingKeyPath, signatures) =>
      new FixHandover(RpcClient(nodeUrl), signingKeyPath).run(signatures)
    }

}
