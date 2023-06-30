package io.iohk.scevm.rpc.sidechain

import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{EpochPhase, MainchainPublicKey}
import io.iohk.scevm.rpc.armadillo.CommonRpcSchemas
import io.iohk.scevm.rpc.sidechain.CrossChainTransactionController.GetOutgoingTransactionsResponse
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, OutgoingTxId, OutgoingTxRecipient}
import io.iohk.scevm.sidechain.{SidechainEpoch, ValidIncomingCrossChainTransaction}
import io.iohk.scevm.trustlesssidechain._
import io.iohk.scevm.trustlesssidechain.cardano._
import sttp.tapir.{Schema, ValidationResult, Validator}

trait SidechainSchemas extends CommonRpcSchemas {
  implicit val schemaForUtxoId: Schema[UtxoId]     = Schema.derived
  implicit val schemaForUtxoInfo: Schema[UtxoInfo] = Schema.derived

  implicit val schemaForRegistrationStatus: Schema[CandidateRegistrationStatus] =
    Schema.schemaForString.as[CandidateRegistrationStatus]
  implicit val schemaForUpcomingFrom: Schema[EffectiveFrom]                            = Schema.derived
  implicit val schemaForPendingDeregistration: Schema[UpcomingRegistrationChange]      = Schema.derived
  implicit val schemaForCandidateRegistrationEntry: Schema[CandidateRegistrationEntry] = Schema.derived
  implicit val schemaForMapOfCandidates: Schema[Map[MainchainPublicKey, List[CandidateRegistrationEntry]]] =
    Schema.schemaForMap[MainchainPublicKey, List[CandidateRegistrationEntry]](k => Hex.toHexString(k.bytes))
  implicit val schemaForGetCommitteeCandidate: Schema[GetCommitteeCandidate]          = Schema.derived
  implicit val schemaForGetCommitteeCandidatesResponse: Schema[GetCandidatesResponse] = Schema.derived

  // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
  implicit val manifestSidechainEpoch: Manifest[SidechainEpoch] = new Manifest[SidechainEpoch] {
    override def runtimeClass: Class[_] = SidechainEpoch.getClass
  }

  implicit val schemaForSidechainEpochParamLatest: Schema[SidechainEpochParam.Latest.type] =
    Schema.schemaForString.as[SidechainEpochParam.Latest.type]
  implicit val schemaForSidechainEpochParamByEpoch: Schema[SidechainEpochParam.ByEpoch] =
    Schema.schemaForLong.map { epoch =>
      Some(SidechainEpochParam.ByEpoch(SidechainEpoch(epoch)))
    }(_.epoch.number)
  implicit val schemaForSidechainEpochParam: Schema[SidechainEpochParam] =
    Schema.derived.validate {
      val msg = "epoch number must be non-negative"
      Validator.custom(
        {
          case SidechainEpochParam.ByEpoch(sidechainEpoch) if sidechainEpoch.number < 0 =>
            ValidationResult.Invalid(msg)
          case _ => ValidationResult.Valid
        },
        Some(msg)
      )
    }
  implicit def schemaForValidLeaderCandidate[SignatureScheme <: AbstractSignatureScheme](implicit
      publicKeySchema: Schema[SignatureScheme#PublicKey]
  ): Schema[ValidLeaderCandidate[SignatureScheme]]                         = Schema.derived
  implicit val schemaForGetCommitteeResponse: Schema[GetCommitteeResponse] = Schema.derived

  implicit val schemaForEpochPhase: Schema[EpochPhase]                                         = Schema.schemaForString.map(EpochPhase.fromRaw.lift)(_.raw)
  implicit val schemaForSidechainBlockData: Schema[SidechainBlockData]                         = Schema.derived
  implicit val schemaForSidechainData: Schema[SidechainData]                                   = Schema.derived
  implicit val schemaForMainchainBlockData: Schema[MainchainBlockData]                         = Schema.derived
  implicit val schemaForMainchainData: Schema[MainchainData]                                   = Schema.derived
  implicit val schemaForGetStatusResponse: Schema[GetStatusResponse]                           = Schema.derived
  implicit val schemaForChainId: Schema[ChainId]                                               = Schema.schemaForByte.as[ChainId]
  implicit val schemaForSidechainParamsThreshold: Schema[SidechainParamsThreshold]             = Schema.derived
  implicit val schemaForGetSidechainParamsResponse: Schema[GetSidechainParamsResponse]         = Schema.derived
  implicit val schemaForMerkleRootHash: Schema[RootHash]                                       = schemaForByteString.as[RootHash]
  implicit val schemaForSidechainParams: Schema[SidechainParams]                               = Schema.derived
  implicit val schemaForCrossChainSignaturesEntry: Schema[CrossChainSignaturesEntry]           = Schema.derived
  implicit val schemaForCommitteeHandoverSignatures: Schema[CommitteeHandoverSignatures]       = Schema.derived
  implicit val schemaForOutgoingTransactionsSignatures: Schema[OutgoingTransactionsSignatures] = Schema.derived
  implicit val schemaForGetSignaturesResponse: Schema[GetSignaturesResponse]                   = Schema.derived
  implicit val schemaForOutgoingTxId: Schema[OutgoingTxId]                                     = OutgoingTxId.deriving
  implicit val schemaForOutgoingTxRecipient: Schema[OutgoingTxRecipient]                       = OutgoingTxRecipient.deriving
  implicit val schemaForOutgoingTransactionSchema: Schema[OutgoingTransaction]                 = Schema.derived
  implicit val schemaForGetOutgoingTransactionsResponse: Schema[GetOutgoingTransactionsResponse] =
    Schema.derived
  implicit val schemaForValidIncomingCrossChainTransaction: Schema[ValidIncomingCrossChainTransaction] = Schema.derived
  implicit val schemaForGetPendingTransactionsResponse: Schema[GetPendingTransactionsResponse]         = Schema.derived

  implicit val schemaForGetMerkleRootResponse: Schema[GetMerkleProofResponse] = {
    implicit val schemaForGetMerkleRootInfoResponse: Schema[GetMerkleProofInfoResponse] = Schema.derived
    implicit val schemaForGetMerkleRootData: Schema[GetMerkleProofData]                 = Schema.derived
    Schema.derived
  }
  implicit lazy val schemaForGetSignaturesToUploadResponse: Schema[GetSignaturesToUploadResponse] = Schema.derived

}
