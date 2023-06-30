package io.iohk.scevm.rpc.sidechain

import cats.data.{EitherT, Ior, NonEmptyList, OptionT}
import cats.syntax.all._
import cats.{Applicative, Monad}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.scevm.cardanofollower.datasource.{
  EpochData,
  LeaderCandidate,
  MainchainDataSource,
  PendingDeregistration,
  PendingRegistration,
  RegistrationData
}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{
  BlockHash,
  BlockNumber,
  EpochPhase,
  MainchainPublicKey,
  ObftHeader,
  SidechainPublicKey,
  SidechainSignatureNoRecovery,
  Slot
}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.rpc.ServiceResponseF
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.domain.JsonRpcError.LogicError
import io.iohk.scevm.rpc.sidechain.SidechainController.CandidateRegistrationStatus.{Active, Deregistered}
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.sidechain.CrossChainSignaturesService.{CrossChainSignatures, MemberSignature}
import io.iohk.scevm.sidechain.MainchainStatusProvider.MainchainStatus
import io.iohk.scevm.sidechain.certificate.MissingCertificatesResolver
import io.iohk.scevm.sidechain.committee.{CandidateRegistration, SidechainCommitteeProvider, SingleRegistrationStatus}
import io.iohk.scevm.sidechain.transactions.MerkleProofService.{SerializedMerkleProof, SerializedMerkleProofWithDetails}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, OutgoingTxId, merkletree}
import io.iohk.scevm.sidechain.{
  InitializationConfig,
  MainchainStatusProvider,
  SidechainEpoch,
  ValidIncomingCrossChainTransaction
}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.{
  Lovelace,
  MainchainBlockInfo,
  MainchainBlockNumber,
  MainchainEpoch,
  MainchainSlot,
  MainchainTxHash,
  UtxoId,
  UtxoInfo
}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.TimestampSeconds
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.InstantToUnixTimestamp

trait SidechainController[F[_]] {

  /** Returns the most recent state of registrations. */
  def getCurrentCandidates: ServiceResponseF[F, GetCandidatesResponse]

  /** Returns the state of candidates registrations at the beginning of given sidechain epoch. */
  def getCandidates(request: GetCandidatesRequest): ServiceResponseF[F, GetCandidatesResponse]

  def getCommittee(request: SidechainEpochParam): ServiceResponseF[F, GetCommitteeResponse]

  def getStatus(): ServiceResponseF[F, GetStatusResponse]

  def getEpochSignatures(req: SidechainEpochParam): ServiceResponseF[F, GetSignaturesResponse]

  def getSidechainParams(): F[GetSidechainParamsResponse]

  def getOutgoingTxMerkleProof(
      request: SidechainEpochParam,
      txId: OutgoingTxId
  ): ServiceResponseF[F, GetMerkleProofResponse]

  def getSignaturesToUpload(limit: Option[Int]): ServiceResponseF[F, GetSignaturesToUploadResponse]
}

object SidechainController {
  val MainchainDataNotAvailableError: JsonRpcError = LogicError("Mainchain data not available")

  sealed trait CandidateRegistrationStatus

  object CandidateRegistrationStatus {
    case object Invalid extends CandidateRegistrationStatus

    case object Pending extends CandidateRegistrationStatus

    case object Active extends CandidateRegistrationStatus

    case object Superseded extends CandidateRegistrationStatus

    case object Deregistered extends CandidateRegistrationStatus

    case object PendingDeregistration extends CandidateRegistrationStatus
  }

  final case class CandidateRegistrationEntry(
      sidechainPubKey: SidechainPublicKey,
      mainchainPubKey: MainchainPublicKey,
      crossChainPubKey: AbstractPublicKey,
      sidechainSignature: SidechainSignatureNoRecovery,
      mainchainSignature: EdDSA.Signature,
      crossChainSignature: AnySignature,
      utxo: UtxoInfo,
      stakeDelegation: Lovelace,
      registrationStatus: CandidateRegistrationStatus,
      upcomingChange: Option[UpcomingRegistrationChange],
      invalidReasons: Option[Seq[String]]
  )

  final case class UpcomingRegistrationChange(
      newState: CandidateRegistrationStatus,
      effectiveFrom: EffectiveFrom,
      mainchainTxHash: MainchainTxHash
  )
  final case class EffectiveFrom(sidechainEpoch: SidechainEpoch, mainchainEpoch: MainchainEpoch)

  final case class GetCandidatesRequest(epoch: SidechainEpochParam)

  final case class GetCandidatesResponse(candidates: List[CandidateRegistrationEntry])

  final case class GetCommitteeResponse(committee: List[GetCommitteeCandidate], sidechainEpoch: SidechainEpoch)
  final case class GetCommitteeCandidate(sidechainPubKey: SidechainPublicKey, stakeDelegation: Lovelace)

  final case class GetStatusResponse(
      sidechain: SidechainData,
      mainchain: MainchainData
  )

  final case class SidechainData(
      bestBlock: SidechainBlockData,
      stableBlock: SidechainBlockData,
      epoch: SidechainEpoch,
      epochPhase: EpochPhase,
      slot: Slot,
      nextEpochTimestamp: TimestampSeconds
  )

  final case class SidechainBlockData(
      number: BlockNumber,
      hash: BlockHash,
      timestamp: TimestampSeconds
  )

  final case class MainchainData(
      bestBlock: MainchainBlockData,
      stableBlock: MainchainBlockData,
      epoch: MainchainEpoch,
      slot: MainchainSlot,
      nextEpochTimestamp: TimestampSeconds
  )

  final case class MainchainBlockData(
      number: MainchainBlockNumber,
      hash: BlockHash,
      timestamp: TimestampSeconds
  )

  final case class GetPendingTransactionsResponse(
      pending: List[ValidIncomingCrossChainTransaction],
      queued: List[ValidIncomingCrossChainTransaction]
  )

  final case class GetSignaturesResponse(
      params: SidechainParams,
      committeeHandover: CommitteeHandoverSignatures,
      outgoingTransactions: List[OutgoingTransactionsSignatures]
  )

  final case class CommitteeHandoverSignatures(
      nextCommitteePubKeys: List[SidechainPublicKey],
      previousMerkleRootHash: Option[ByteString],
      signatures: List[CrossChainSignaturesEntry]
  )

  final case class CrossChainSignaturesEntry(committeeMember: SidechainPublicKey, signature: ByteString)

  final case class OutgoingTransactionsSignatures(
      merkleRootHash: RootHash,
      previousMerkleRootHash: Option[ByteString],
      signatures: List[CrossChainSignaturesEntry]
  )

  final case class GetSidechainParamsResponse(
      genesisMintUtxo: Option[UtxoId],
      genesisCommitteeUtxo: UtxoId,
      genesisHash: BlockHash,
      chainId: ChainId,
      threshold: SidechainParamsThreshold
  )

  final case class SidechainParamsThreshold(numerator: Int, denominator: Int)

  final case class InitializationParams(sidechainParams: SidechainParams, initializationConfig: InitializationConfig)

  final case class GetMerkleProofResponse(proof: Option[GetMerkleProofData], sidechainEpoch: SidechainEpoch)
  final case class GetMerkleProofData(bytes: SerializedMerkleProof, info: GetMerkleProofInfoResponse)
  final case class GetMerkleProofInfoResponse(transaction: OutgoingTransaction, merkleRootHash: merkletree.RootHash)

  type GetSignaturesToUploadResponse = List[EpochAndRootHashes]

  // empty `rootHashes` means all transaction batches were uploaded to mainchain, but handover signatures were not
  final case class EpochAndRootHashes(epoch: SidechainEpoch, rootHashes: List[ByteString])

  trait CandidateRegistrationValidator[F[_], SignatureScheme <: AbstractSignatureScheme] {
    def validateCandidate(candidate: LeaderCandidate[SignatureScheme]): F[CandidateRegistration[SignatureScheme]]
  }

  trait CrossChainSignaturesService[F[_]] {
    def getSignatures(epoch: SidechainEpoch): F[Either[String, CrossChainSignatures]]
  }

  trait MerkleProofService[F[_]] {
    def getProof(epoch: SidechainEpoch, txId: OutgoingTxId): F[Option[SerializedMerkleProofWithDetails]]
  }
}

class SidechainControllerImpl[F[_]: Monad: SystemTime, SignatureScheme <: AbstractSignatureScheme](
    committeeProvider: SidechainCommitteeProvider[F, SignatureScheme],
    mainchainStatusProvider: MainchainStatusProvider[F],
    epochCalculator: EpochCalculator[F],
    mainchainDataSource: MainchainDataSource[F, SignatureScheme],
    candidateValidator: CandidateRegistrationValidator[F, SignatureScheme],
    blockProvider: BlockProvider[F],
    crossChainSignaturesService: CrossChainSignaturesService[F],
    merkleProofService: MerkleProofService[F],
    initializationParams: InitializationParams,
    missingCertificatesResolver: MissingCertificatesResolver[F]
) extends SidechainController[F] {

  /** When queried by ByEpoch(K) for any epoch K that is not in the future, its result should not change.
    * When queried by Latest, should return the same result each time invoked within same epoch.
    * This requires returning data from the beginning of the requested sidechain epoch.
    */
  override def getCandidates(request: GetCandidatesRequest): ServiceResponseF[F, GetCandidatesResponse] =
    (for {
      mainchainSlot <- OptionT.liftF(getMainchainSlot(request))
      epochData     <- OptionT(mainchainDataSource.getMainchainData(mainchainSlot))
    } yield epochData).value.flatMap(epochDataOptToResponse)

  /** Returns data for the most recent observed mainchain slot
    */
  override def getCurrentCandidates: ServiceResponseF[F, GetCandidatesResponse] =
    for {
      timestamp    <- SystemTime[F].realTime()
      mainchainSlot = epochCalculator.getMainchainSlot(timestamp)
      epochData    <- mainchainDataSource.getMainchainData(mainchainSlot)
      response     <- epochDataOptToResponse(epochData)
    } yield response

  private def epochDataOptToResponse(
      epochData: Option[EpochData[SignatureScheme]]
  ): F[Either[JsonRpcError, GetCandidatesResponse]] =
    for {
      registrations <- epochData.map(_.candidates).getOrElse(List.empty).traverse { candidate =>
                         candidateValidator.validateCandidate(candidate)
                       }
      registrationEntries = flattenModel(registrations)
    } yield GetCandidatesResponse(registrationEntries).asRight[JsonRpcError]

  private def flattenModel(
      registrations: List[CandidateRegistration[SignatureScheme]]
  ): List[CandidateRegistrationEntry] = {
    def convertInvalidAndPending(
        mainchainKey: MainchainPublicKey,
        pendingAndInvalid: Ior[NonEmptyList[SingleRegistrationStatus.Invalid[SignatureScheme]], NonEmptyList[
          SingleRegistrationStatus.Pending[SignatureScheme]
        ]]
    ) = {
      val pendingRpc = convertStatusesToRpcModel(mainchainKey, _, CandidateRegistrationStatus.Pending)
      val invalidRpc = convertStatusesToRpcModel(mainchainKey, _, CandidateRegistrationStatus.Invalid)
      pendingAndInvalid
        .bimap(_.toList, _.toList)
        .bifoldMap(invalidRpc, pendingRpc)
    }

    registrations.flatMap {
      case CandidateRegistration.Active(reg, mainchainKey, superseded, pending, invalid) =>
        val status = reg.pendingChange match {
          case Some(PendingDeregistration(_, _)) => CandidateRegistrationStatus.PendingDeregistration
          case _                                 => CandidateRegistrationStatus.Active
        }
        val activeRpc     = convertToRpcModel(mainchainKey, reg, status, None)
        val supersededRpc = convertStatusesToRpcModel(mainchainKey, superseded, CandidateRegistrationStatus.Superseded)
        val pendingRpc    = convertStatusesToRpcModel(mainchainKey, pending, CandidateRegistrationStatus.Pending)
        val invalidRpc    = convertStatusesToRpcModel(mainchainKey, invalid, CandidateRegistrationStatus.Invalid)
        List(activeRpc) ++ pendingRpc ++ invalidRpc ++ supersededRpc
      case CandidateRegistration.Inactive(pubKey, invalidAndPending) =>
        convertInvalidAndPending(pubKey, invalidAndPending)
    }
  }

  private def convertStatusesToRpcModel(
      mainchainPubKey: MainchainPublicKey,
      statuses: List[SingleRegistrationStatus[SignatureScheme]],
      status: SidechainController.CandidateRegistrationStatus
  ): List[CandidateRegistrationEntry] =
    statuses.map {
      case SingleRegistrationStatus.Invalid(registrationData, reasons) =>
        convertToRpcModel(mainchainPubKey, registrationData, status, Some(reasons.toList))
      case s =>
        convertToRpcModel(mainchainPubKey, s.registrationData, status, None)
    }

  private def convertToRpcModel(
      mainchainPubKey: MainchainPublicKey,
      data: RegistrationData[SignatureScheme],
      status: SidechainController.CandidateRegistrationStatus,
      invalidReasons: Option[Seq[String]]
  ): CandidateRegistrationEntry =
    CandidateRegistrationEntry(
      data.sidechainPubKey,
      mainchainPubKey,
      data.crossChainPubKey,
      data.sidechainSignature,
      data.mainchainSignature,
      data.crossChainSignature,
      data.utxoInfo,
      data.stakeDelegation,
      status,
      upcomingChange(data, status),
      invalidReasons
    )

  private def upcomingChange(
      data: RegistrationData[SignatureScheme],
      status: CandidateRegistrationStatus
  ): Option[UpcomingRegistrationChange] = {
    def effectiveFrom(mainchainEpoch: MainchainEpoch): EffectiveFrom =
      EffectiveFrom(epochCalculator.getFirstSidechainEpoch(mainchainEpoch), mainchainEpoch)

    data.pendingChange.collect {
      case PendingRegistration(txHash, effectiveAt) if status == CandidateRegistrationStatus.Pending =>
        UpcomingRegistrationChange(Active, effectiveFrom(effectiveAt), txHash)
      case PendingDeregistration(txHash, effectiveAt) =>
        UpcomingRegistrationChange(Deregistered, effectiveFrom(effectiveAt), txHash)
    }
  }

  private def getMainchainSlot(request: GetCandidatesRequest): F[MainchainSlot] =
    request.epoch match {
      case SidechainEpochParam.Latest =>
        for {
          sidechainEpoch <- epochCalculator.getCurrentEpoch
          mainchainSlot   = epochCalculator.getFirstMainchainSlot(sidechainEpoch)
        } yield mainchainSlot
      case SidechainEpochParam.ByEpoch(sidechainEpoch) =>
        epochCalculator.getFirstMainchainSlot(sidechainEpoch).pure
    }

  override def getCommittee(epoch: SidechainEpochParam): ServiceResponseF[F, GetCommitteeResponse] =
    (epoch match {
      case SidechainEpochParam.Latest =>
        EitherT
          .liftF(epochCalculator.getCurrentEpoch)
          .flatMap(e => EitherT(committeeProvider.getCommittee(e)).map((_, e)))
      case SidechainEpochParam.ByEpoch(epoch) =>
        EitherT(committeeProvider.getCommittee(epoch)).map((_, epoch))
    }).value.map {
      case Left(failure) => Left(LogicError(show"$failure"))
      case Right((result, epoch)) =>
        Right(
          GetCommitteeResponse(
            result.committee.toList.map(lc => GetCommitteeCandidate(lc.pubKey, lc.stakeDelegation)),
            epoch
          )
        )
    }

  override def getStatus(): ServiceResponseF[F, GetStatusResponse] = {
    def toSidechainBlockData(b: ObftHeader) =
      SidechainBlockData(b.number, b.hash, b.unixTimestamp.toTimestampSeconds)

    def toMainchainBlockData(b: MainchainBlockInfo) =
      MainchainBlockData(b.number, b.hash, b.instant.toTs.toTimestampSeconds)

    mainchainStatusProvider.getStatus.flatMap {
      case MainchainStatus(Some(latestMainchainBlock), Some(stableMainchainBlock)) =>
        for {
          latestSidechainBlockHeader <- blockProvider.getBestBlockHeader
          sidechainEpoch              = epochCalculator.getSidechainEpoch(latestSidechainBlockHeader.slotNumber)
          sidechainEpochPhase         = epochCalculator.getSidechainEpochPhase(latestSidechainBlockHeader.slotNumber)
          nextMainchainEpoch          = epochCalculator.getMainchainEpochStartTime(latestMainchainBlock.epoch.next)
          nextSidechainEpoch          = epochCalculator.getSidechainEpochStartTime(sidechainEpoch.next)
          sidechainStableBlock       <- blockProvider.getStableBlockHeader
        } yield GetStatusResponse(
          SidechainData(
            toSidechainBlockData(latestSidechainBlockHeader),
            toSidechainBlockData(sidechainStableBlock),
            sidechainEpoch,
            sidechainEpochPhase,
            latestSidechainBlockHeader.slotNumber,
            nextSidechainEpoch
          ),
          MainchainData(
            toMainchainBlockData(latestMainchainBlock),
            toMainchainBlockData(stableMainchainBlock),
            latestMainchainBlock.epoch,
            latestMainchainBlock.slot,
            nextMainchainEpoch
          )
        ).asRight[JsonRpcError]
      case _ =>
        Applicative[F].pure(MainchainDataNotAvailableError.asLeft[GetStatusResponse])
    }
  }

  override def getEpochSignatures(req: SidechainEpochParam): ServiceResponseF[F, GetSignaturesResponse] =
    (req match {
      case SidechainEpochParam.Latest =>
        epochCalculator.getCurrentEpoch
          .flatMap(crossChainSignaturesService.getSignatures)
      case SidechainEpochParam.ByEpoch(epoch) =>
        crossChainSignaturesService.getSignatures(epoch)
    }).map {
      case Left(failure) => Left(LogicError(show"$failure"))
      case Right(CrossChainSignatures(sidechainParams, committeeHandover, outgoingTransactions)) =>
        Right(
          GetSignaturesResponse(
            sidechainParams,
            CommitteeHandoverSignatures(
              committeeHandover.nextCommitteePublicKeys,
              committeeHandover.previousMerkleRoot.map(_.value),
              committeeHandover.signatures.map { case MemberSignature(member, signatures) =>
                CrossChainSignaturesEntry(
                  member,
                  signatures.toBytes
                )
              }
            ),
            outgoingTransactions
              .map(outgoingTxs =>
                OutgoingTransactionsSignatures(
                  outgoingTxs.transactions,
                  outgoingTxs.previousMerkleRoot.map(_.value),
                  outgoingTxs.signatures.map { case MemberSignature(member, signatures) =>
                    CrossChainSignaturesEntry(
                      member,
                      signatures.toBytes
                    )
                  }
                )
              )
          )
        )
    }

  override def getSidechainParams(): F[GetSidechainParamsResponse] =
    GetSidechainParamsResponse(
      initializationParams.initializationConfig.genesisMintUtxo,
      initializationParams.initializationConfig.genesisCommitteeUtxo,
      initializationParams.sidechainParams.genesisHash,
      initializationParams.sidechainParams.chainId,
      SidechainParamsThreshold(
        numerator = initializationParams.sidechainParams.thresholdNumerator,
        denominator = initializationParams.sidechainParams.thresholdDenominator
      )
    ).pure

  override def getOutgoingTxMerkleProof(
      request: SidechainEpochParam,
      txId: OutgoingTxId
  ): ServiceResponseF[F, GetMerkleProofResponse] =
    (request match {
      case SidechainEpochParam.Latest =>
        epochCalculator.getCurrentEpoch
          .flatMap(se => merkleProofService.getProof(se, txId).product(se.pure[F]))
      case SidechainEpochParam.ByEpoch(epoch) =>
        merkleProofService.getProof(epoch, txId).product(epoch.pure[F])
    }).map { case (maybeProof, epoch) =>
      GetMerkleProofResponse(
        maybeProof.map { case SerializedMerkleProofWithDetails(merkleProof, additionalInfo) =>
          GetMerkleProofData(
            merkleProof,
            GetMerkleProofInfoResponse(additionalInfo.outgoingTransaction, additionalInfo.currentRootHash)
          )
        },
        epoch
      ).asRight[JsonRpcError]
    }

  override def getSignaturesToUpload(limit: Option[Int]): ServiceResponseF[F, GetSignaturesToUploadResponse] = {
    val DefaultLimit = 100
    missingCertificatesResolver
      .getMissingCertificates(limit.getOrElse(DefaultLimit))
      .map {
        case Left(error) => Left(JsonRpcError.LogicError(error))
        case Right(missing) =>
          missing
            .map { case (epoch, rootHashes) => EpochAndRootHashes(epoch, rootHashes.map(_.value)) }
            .asRight[JsonRpcError]
      }
  }
}
