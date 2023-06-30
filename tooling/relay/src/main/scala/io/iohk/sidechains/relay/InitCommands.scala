package io.iohk.sidechains.relay

import cats.effect.IO
import cats.syntax.all._
import io.iohk.sidechains.relay.CtlParameters.{
  Committee,
  makeCommitteeParams,
  makeSidechainEpochParams,
  makeSidechainParams,
  makeSigningKeyParams
}
import io.iohk.sidechains.relay.RpcFormats.{GetSignaturesResponse, SidechainParams}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import RpcClient.RpcClientOps._

/** Responsibility: decide if it's required to 'init' chain and provide sidechain-main-cli command for it. */
class InitCommands(rpcClient: RpcClient, signingKeyPath: Option[String]) {

  implicit val log: Logger[IO] = Slf4jLogger.getLogger[IO]

  def getCtlCommand: IO[Option[Command]] =
    /*
     * sidechain_getSignaturesToUpload returns 200 => init was done already.
     * Current sidechain epoch 0 => chain isn't started, to early to init.
     * Otherwise go back through epochs to find the first one with signatures.
     * 'init' committee with public keys of signatures providers, but with the previous epoch.
     * */
    signingKeyPath match {
      case None =>
        log.info(s"init signing key is not given, skipping chain init.").as(none)
      case Some(signingKeyPath) =>
        checkIfInitialized.flatMap { initialized =>
          if (initialized)
            IO.pure(none)
          else
            firstEpochWithSignatures.map(_.map { case (epoch, signatures) =>
              initCommand(
                epoch - 1,
                signatures.params,
                signatures.committeeHandover.signatures.map(_.committeeMember),
                signingKeyPath
              )
            })
        }
    }

  private def checkIfInitialized: IO[Boolean] =
    rpcClient.getNextEpoch.attempt
      .flatMap {
        case Left(_) =>
          log.info(s"sidechain_getSignaturesToUpload failed, assuming chain is not initialized.").as(false)
        case Right(_) => log.info(s"sidechain_getSignaturesToUpload succeeded, chain is initialized already.").as(true)
      }

  private def firstEpochWithSignatures: IO[Option[(Long, GetSignaturesResponse)]] = for {
    currentEpoch             <- rpcClient.getStatus.map(_.sidechain.epoch)
    firstEpochWithSignatures <- findFirstEpochWithEnoughSignatures(currentEpoch - 1) // Start from finished epoch
  } yield firstEpochWithSignatures

  private def findFirstEpochWithEnoughSignatures(latestEpoch: Long): IO[Option[(Long, GetSignaturesResponse)]] = {
    def moveBackwards(epoch: Long, epochData: GetSignaturesResponse): IO[Option[(Long, GetSignaturesResponse)]] =
      getEpochSignatures(epoch - 1).flatMap {
        case Some(previousEpochData) =>
          moveBackwards(epoch - 1, previousEpochData)
        case _ =>
          log
            .info(s"Epoch $epoch committee is the earliest with enough signatures. Using it for 'init' command.")
            .as((epoch, epochData).some)
      }

    // If the latest epoch doesn't have enough signatures, none epoch will have
    getEpochSignatures(latestEpoch).flatMap {
      case Some(r) => moveBackwards(latestEpoch, r)
      case _       => log.info(s"The latest epoch doesn't have enough signatures, cannot initialize sidechain yet.").as(none)
    }
  }

  private def getEpochSignatures(epoch: Long): IO[Option[GetSignaturesResponse]] =
    rpcClient
      .getEpochSignatures(epoch)
      .attempt
      .map {
        case Right(r) if enoughSignaturesGiven(r) => Some(r)
        case _                                    => None
      }

  private def enoughSignaturesGiven(signaturesResponse: GetSignaturesResponse): Boolean = {
    val signaturesNumber = signaturesResponse.committeeHandover.signatures.length
    val committeeSize    = signaturesResponse.committeeHandover.nextCommitteePubKeys.length
    val numerator        = signaturesResponse.params.thresholdNumerator
    val denominator      = signaturesResponse.params.thresholdDenominator
    signaturesNumber > (committeeSize * numerator / denominator)
  }

  private def initCommand(
      epoch: Long,
      sidechainParams: SidechainParams,
      committee: Committee,
      signingKeyPath: String
  ): Command =
    List("sidechain-main-cli", "init") ++
      makeSidechainParams(sidechainParams) ++
      makeSidechainEpochParams(epoch) ++
      makeCommitteeParams(committee) ++
      makeSigningKeyParams(signingKeyPath)
}
