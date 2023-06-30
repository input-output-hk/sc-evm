package io.iohk.sidechains.relay

import cats.effect.IO
import cats.syntax.all._
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignatureNoRecovery}
import io.iohk.sidechains.relay.EpochCommands._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import RpcClient.RpcClientOps._
import CtlParameters._
import RpcFormats._

/** Responsibility: provide sidechain-main-cli commands to relay epochs. */
class EpochCommands(rpcClient: RpcClient, signingKeyPath: String) {

  implicit val log: Logger[IO] = Slf4jLogger.getLogger[IO]

  def getCtlCommands: IO[List[Command]] =
    for {
      epochsToUpload <- getEpochsToUpload
      epochsCommands <- epochsToUpload.traverse(epochCommands)
    } yield epochsCommands.flatten

  private def getEpochsToUpload: IO[List[EpochToUpload]] =
    for {
      epochs <- rpcClient.getNextEpochs(10)
      _      <- log.info(s"Number of epochs to relay: ${epochs.length}")
    } yield epochs

  private def epochCommands(e: EpochToUpload): IO[List[Command]] = for {
    _          <- log.info(s"Getting data for epoch and merkle roots: $e")
    committee  <- rpcClient.getCommittee(e.epoch)
    signatures <- rpcClient.getEpochSignatures(e.epoch)
  } yield saveRoots(e, committee, signatures) :+
    committeeHash(e.epoch, signatures.params, committee, signatures.committeeHandover, signingKeyPath)

  private def saveRoots(
      epochToUpload: EpochToUpload,
      committee: CommitteeResponse,
      signatures: GetSignaturesResponse
  ): List[Command] =
    signatures.outgoingTransactions
      .filter(txs => epochToUpload.rootHashes.contains(txs.merkleRootHash))
      .map(txsSignatures =>
        saveRoot(signatures.params, committee.committee.map(_.sidechainPubKey), txsSignatures, signingKeyPath)
      )

}

object EpochCommands {

  def saveRoot(
      sidechainParams: SidechainParams,
      committee: Committee,
      txsSignatures: OutgoingTransactionsSignatures,
      signingKeyPath: String
  ): Command =
    List("sidechain-main-cli", "save-root") ++
      makeSidechainParams(sidechainParams) ++
      makeMerkleRootParams(txsSignatures.merkleRootHash) ++
      makePreviousMerkleRootParams(txsSignatures.previousMerkleRootHash) ++
      makeSignatureParameters(committee, txsSignatures.signatures) ++
      makeSigningKeyParams(signingKeyPath)

  def saveRoot(
      sidechainParams: SidechainParams,
      committee: List[ECDSA.PublicKey],
      merkleRootHash: String,
      previousMerkleRootHash: Option[String],
      txsSignatures: List[(ECDSA.PublicKey, ECDSASignatureNoRecovery)],
      signingKeyPath: String
  ): Command =
    List("sidechain-main-cli", "save-root") ++
      makeSidechainParams(sidechainParams) ++
      makeMerkleRootParams(merkleRootHash) ++
      makePreviousMerkleRootParams(previousMerkleRootHash) ++
      makeSignatureParametersFromKeys(committee, txsSignatures) ++
      makeSigningKeyParams(signingKeyPath)

  def committeeHash(
      epoch: Long,
      sidechainParams: SidechainParams,
      committee: CommitteeResponse,
      handover: CommitteeHandoverSignatures,
      signingKeyPath: String
  ): Command =
    List("sidechain-main-cli", "committee-hash") ++
      makeSidechainParams(sidechainParams) ++
      makePreviousMerkleRootParams(handover.previousMerkleRootHash) ++
      makeSignatureParameters(committee.committee.map(_.sidechainPubKey), handover.signatures) ++
      makeNewCommitteeParams(handover.nextCommitteePubKeys) ++
      makeSidechainEpochParams(epoch) ++
      makeSigningKeyParams(signingKeyPath)

  def committeeHash(
      epoch: Long,
      sidechainParams: SidechainParams,
      committee: List[ECDSA.PublicKey],
      nextCommitteePubKeys: List[ECDSA.PublicKey],
      signatures: List[(ECDSA.PublicKey, ECDSASignatureNoRecovery)],
      previousMerkleRootHash: Option[String],
      signingKeyPath: String
  ): Command =
    List("sidechain-main-cli", "committee-hash") ++
      makeSidechainParams(sidechainParams) ++
      makePreviousMerkleRootParams(previousMerkleRootHash) ++
      makeSignatureParametersFromKeys(committee, signatures) ++
      makeNewCommitteeParamsFromKey(nextCommitteePubKeys) ++
      makeSidechainEpochParams(epoch) ++
      makeSigningKeyParams(signingKeyPath)

}
