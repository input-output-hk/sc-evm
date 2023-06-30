package io.iohk.scevm.sidechain.consensus

import cats.effect.Sync
import io.iohk.bytes.FromBytes
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.consensus.validators.{
  CompositePostExecutionValidator,
  PostExecutionValidator,
  PostExecutionValidatorImpl
}
import io.iohk.scevm.domain.UInt256
import io.iohk.scevm.exec.vm.EvmCall
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain._
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import org.typelevel.log4cats.LoggerFactory

object SidechainPostExecutionValidator {

  def apply[F[_]: Sync: LoggerFactory, SignatureScheme <: AbstractSignatureScheme](
      unprocessedCrossChainTransactionsProvider: IncomingCrossChainTransactionsProvider[F],
      sidechainBlockchainConfig: SidechainBlockchainConfig,
      bridgeContract: HandoverTransactionProvider.BridgeContract[EvmCall[F, *], SignatureScheme],
      epochDerivation: SidechainEpochDerivation[F],
      getCommittee: SidechainEpoch => F[Either[ElectionFailure, CommitteeElectionSuccess[SignatureScheme]]],
      tokenConversionRate: UInt256
  )(implicit crossChainSignatureFromBytes: FromBytes[SignatureScheme#Signature]): PostExecutionValidator[F] = {
    val bridgeAddress = sidechainBlockchainConfig.bridgeContractAddress
    val incomingTransactionValidator =
      new IncomingCrossChainTransactionsValidator[F](
        unprocessedCrossChainTransactionsProvider,
        bridgeAddress,
        tokenConversionRate
      )
    val sidechainParams = sidechainBlockchainConfig.sidechainParams
    val handoverSignatureValidator =
      new HandoverSignatureValidator[F, SignatureScheme](
        bridgeAddress,
        sidechainParams,
        bridgeContract,
        epochDerivation,
        getCommittee,
        new CommitteeHandoverSigner[F]
      )
    val outgoingTransactionSignatureValidator =
      new OutgoingTransactionSignatureValidator(
        bridgeAddress,
        sidechainParams,
        bridgeContract,
        epochDerivation,
        getCommittee
      )
    val firstBlockValidator  = new FirstBlockIsNotFromHandoverPhaseValidator[F](epochDerivation)
    val basicHeaderValidator = new PostExecutionValidatorImpl[F]
    new CompositePostExecutionValidator[F](
      List(
        basicHeaderValidator,
        incomingTransactionValidator,
        handoverSignatureValidator,
        outgoingTransactionSignatureValidator,
        firstBlockValidator
      )
    )
  }
}
