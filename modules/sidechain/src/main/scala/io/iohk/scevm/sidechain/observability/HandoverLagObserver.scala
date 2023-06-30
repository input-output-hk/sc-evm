package io.iohk.scevm.sidechain.observability

import cats.MonadThrow
import cats.data.{EitherT, NonEmptyVector}
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.{MainchainActiveFlowDataSource, ValidLeaderCandidate}
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.metrics.SidechainMetrics
import io.iohk.scevm.sidechain.{ObservabilityConfig, SidechainEpochDerivation}
import io.iohk.scevm.trustlesssidechain.cardano.{Blake2bHash32, EcdsaCompressedPubKey}
import org.typelevel.log4cats.{Logger, LoggerFactory, SelfAwareStructuredLogger}

class HandoverLagObserver[F[_]: MonadThrow: LoggerFactory, Scheme <: AbstractSignatureScheme](
    mainchainActiveFlowDataProvider: MainchainActiveFlowDataSource[F],
    sidechainMetrics: SidechainMetrics[F],
    epochDerivation: SidechainEpochDerivation[F],
    committeeProvider: SidechainCommitteeProvider[F, Scheme],
    config: ObservabilityConfig
) extends ObserverScheduler.Observer[F] {

  implicit private val logF: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def apply(): F[Unit] =
    mainchainActiveFlowDataProvider
      .getLatestCommitteeNft()
      .flatMap {
        case None => Logger[F].warn(show"Could not find any committee nft. Is the sidechain initialized?")
        case Some(committeeNft) =>
          for {
            currentEpoch <- epochDerivation.getCurrentEpoch
            // previous epoch contains current committee hash as datum, so when comparing with the committee from the provider we need to use the next epoch
            nextOnChainEpoch = committeeNft.sidechainEpoch.next
            committeeForOnChainEpoch <-
              EitherT(committeeProvider.getCommittee(nextOnChainEpoch))
                .foldF(f => new RuntimeException(f.show).raiseError[F, CommitteeElectionSuccess[Scheme]], _.pure[F])

            offChainCommitteeHash = committeeHash(committeeForOnChainEpoch.committee)
            _ <- if (offChainCommitteeHash =!= committeeNft.committeeHash) {
                   Logger[F].error(
                     show"Latest on-chain committee doesn't match committee calculated by sidechain for the epoch $nextOnChainEpoch"
                   )
                 } else {
                   Logger[F].debug(show"On-chain committee matches the committee calculated by sidechain")
                 }
            // Don't count the current epoch, otherwise we will be always lagging behind by 1 epoch
            handoverLagInEpochs = currentEpoch.number - committeeNft.sidechainEpoch.number - 1
            _                  <- sidechainMetrics.mainchainFollowerCommitteeNftLag.set(handoverLagInEpochs)
            _ <- if (handoverLagInEpochs >= config.handoverLagThreshold) {
                   Logger[F]
                     .warn(show"Latest on-chain handover is lagging behind by $handoverLagInEpochs epochs")
                 } else {
                   Logger[F].debug(
                     show"Latest on-chain handover is lagging behind by $handoverLagInEpochs epochs"
                   )
                 }
          } yield ()
      }

  private def committeeHash(committee: NonEmptyVector[ValidLeaderCandidate[Scheme]]) = {
    val nextCommitteeCompressedKeys = committee
      .map(key => EcdsaCompressedPubKey(key.pubKey.compressedBytes))
      .sortBy(key => Hex.toHexString(key.bytes))
    val concatenatedKeys = nextCommitteeCompressedKeys.foldLeft(ByteString.empty)((acc, key) => acc.concat(key.bytes))
    Blake2bHash32.hash(concatenatedKeys)
  }
}
