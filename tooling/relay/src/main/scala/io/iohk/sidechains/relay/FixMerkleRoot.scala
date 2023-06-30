package io.iohk.sidechains.relay

import cats.data.{EitherT, NonEmptyList}
import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignatureNoRecovery}
import io.iohk.sidechains.relay.Relay.log
import io.iohk.sidechains.relay.RpcClient.RpcClientOps._
import io.iohk.sidechains.relay.RpcFormats.{CommitteeResponse, GetSignaturesResponse}
import sttp.model.Uri

class FixMerkleRoot(rpcClient: RpcClient, signingKeyPath: String) {

  def run(signatures: NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)]): IO[ExitCode] = {
    val task: IO[ExitCode] = (for {
      epochToFix <- EitherT(rpcClient.getNextEpoch).subflatMap(epoch =>
                      if (epoch.rootHashes.length > 1) Left("several root hash per epoch is not handled")
                      else Right(epoch)
                    )
      _ <- EitherT.liftF(log.info(s"Will fix epoch ${epochToFix.epoch}"))
      signaturesFromNode <-
        EitherT.liftF(
          rpcClient.getEpochSignatures(epochToFix.epoch)
        )
      committeeResponse <-
        EitherT.liftF(
          rpcClient.getCommittee(epochToFix.epoch)
        )
      command <- EitherT.fromEither[IO](buildCommand(signatures, signaturesFromNode, committeeResponse))
      _       <- EitherT.liftF[IO, String, String](ProcessIO.execute(command))
    } yield ExitCode.Success)
      .foldF(reason => log.error(s"fixup failed: $reason").as(ExitCode.Error), _.pure[IO])

    task.attempt.flatMap {
      case Left(exception) =>
        (log.error(s"fixup failed: $exception") >> IO(exception.printStackTrace())).as(ExitCode.Error)
      case Right(other) => IO.pure(other)
    }
  }

  private def buildCommand(
      signaturesFromCommand: NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)],
      signaturesFromNode: GetSignaturesResponse,
      committeeResponse: CommitteeResponse
  ): Either[String, Command] = {
    val committee = committeeResponse.parseCommittee
    val signatures = signaturesFromCommand
      .filter { case (pubKey, _) => committee.contains(pubKey) }

    // We could get the previous merkle root from the arguments, but the issue is that then the SC EVM node
    // still would not know about it. So even if in theory one could still claim their token then would
    // not be able to get the proof from the EVM sidechain node.
    signaturesFromNode.outgoingTransactions.headOption
      .toRight(
        "No outgoing transaction was registered for this epoch. " +
          "In order to stay consistent with the state of the sidechain, at least one validator must have signed the" +
          " merkle root during the handover. Otherwise there is no way for a user to get the proof from the node."
      )
      .map(existingSignature =>
        EpochCommands.saveRoot(
          sidechainParams = signaturesFromNode.params,
          committee = committee,
          merkleRootHash = existingSignature.merkleRootHash,
          previousMerkleRootHash = existingSignature.previousMerkleRootHash,
          txsSignatures = signatures,
          signingKeyPath = signingKeyPath: String
        )
      )

  }

}

object FixMerkleRoot {

  final private case class Params(
      nodeUrl: Uri,
      signingKeyPath: String,
      signatures: NonEmptyList[(ECDSA.PublicKey, ECDSASignatureNoRecovery)]
  )

  private lazy val opts: Opts[Params] =
    Opts.subcommand("fix-merkle-root", """Send a "fix" merkle root by manually providing the signatures""")(
      (CommonArgs.nodeUrl, CommonArgs.signingKeyPath, CommonArgs.signaturesArg).mapN(Params.apply)
    )

  def apply(): Opts[IO[ExitCode]] =
    opts.map { case Params(nodeUrl, signingKeyPath, signatures) =>
      new FixMerkleRoot(RpcClient(nodeUrl), signingKeyPath).run(signatures)
    }

}
