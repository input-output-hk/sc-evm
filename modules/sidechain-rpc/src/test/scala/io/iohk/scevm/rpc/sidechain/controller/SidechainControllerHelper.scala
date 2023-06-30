package io.iohk.scevm.rpc.sidechain.controller

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.{LeaderCandidate, MainchainDataSource}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockHash, EpochPhase, ObftBlock, Slot}
import io.iohk.scevm.ledger.BlockProviderStub
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.rpc.sidechain.SidechainController.{
  CandidateRegistrationValidator,
  CrossChainSignaturesService,
  InitializationParams,
  MerkleProofService
}
import io.iohk.scevm.rpc.sidechain.{EpochCalculator, SidechainControllerImpl}
import io.iohk.scevm.sidechain.MainchainStatusProvider.MainchainStatus
import io.iohk.scevm.sidechain.SidechainFixtures.{MainchainEpochDerivationStub, SidechainEpochDerivationStub}
import io.iohk.scevm.sidechain._
import io.iohk.scevm.sidechain.certificate.MissingCertificatesResolver
import io.iohk.scevm.sidechain.committee.{CandidateRegistrationValidatorImpl, SidechainCommitteeProvider}
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.{Lovelace, MainchainEpoch, MainchainSlot, UtxoId}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}

object SidechainControllerHelper {

  implicit val systemTime: SystemTime[IO] = SystemTime.liveF[IO].unsafeRunSync()

  val sidechainParams: SidechainParams =
    SidechainParams(
      ChainId(1),
      BlockHash(Hex.decodeUnsafe("abcdef2b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13")),
      UtxoId.parseUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0"),
      7,
      9
    )

  val initializationParams: InitializationParams = InitializationParams(
    sidechainParams,
    InitializationConfig(
      Some(UtxoId.parseUnsafe("2aaff79a2f884bf6e39fe1558d5b77862c3046c44b998f128ceb1815f77a4d28#1")),
      UtxoId.parseUnsafe("2aaff79a2f884bf6e39fe1558d5b77862c3046c44b998f128ceb1815f77a4d28#0")
    )
  )

  //scalastyle:off method.length
  //scalastyle:off parameter.number
  def createSidechainController[Scheme <: AbstractSignatureScheme](
      committeeProvider: SidechainCommitteeProvider[IO, Scheme] = CommitteeProviderStub[IO, Scheme](
        Left(ElectionFailure.notEnoughCandidates)
      ),
      statusProvider: MainchainStatusProvider[IO] = MainchainStatusProviderStub[IO](MainchainStatus(None, None)),
      sidechainEpochDerivation: SidechainEpochDerivation[IO] = SidechainEpochDerivationStub[IO](),
      mainchainEpochDerivation: MainchainEpochDerivation = MainchainEpochDerivationStub(),
      mainchainDataSource: MainchainDataSource[IO, Scheme] = MainchainDataSourceStub[IO, Scheme](None),
      candidateValidator: CandidateRegistrationValidator[IO, Scheme] = new CandidateRegistrationValidator[IO, Scheme] {
        def validateCandidate(candidate: LeaderCandidate[Scheme]) =
          new CandidateRegistrationValidatorImpl[IO, Scheme](
            sidechainParams,
            minimalStakeThreshold = Lovelace(1)
          ).validateCandidate(candidate).pure[IO]
      },
      crossChainSignaturesService: CrossChainSignaturesService[IO] =
        CrossChainSignaturesServiceStub[IO](Left("no response")),
      bestBlock: ObftBlock = obftBlockGen.sample.get,
      stableBlock: ObftBlock = obftBlockGen.sample.get,
      initializationParams: InitializationParams = SidechainControllerHelper.initializationParams,
      merkleProofService: MerkleProofService[IO] = (_: SidechainEpoch, _: transactions.OutgoingTxId) =>
        IO.pure(Option.empty),
      missingCertificatesResolver: MissingCertificatesResolver[IO] = _ => IO.pure(List.empty.asRight)
  ): SidechainControllerImpl[IO, Scheme] = {
    val blockProvider = BlockProviderStub[IO](List(bestBlock, stableBlock), bestBlock, stableBlock)
    new SidechainControllerImpl[IO, Scheme](
      committeeProvider,
      statusProvider,
      SidechainControllerHelper.epochCalculator(sidechainEpochDerivation, mainchainEpochDerivation),
      mainchainDataSource,
      candidateValidator,
      blockProvider,
      crossChainSignaturesService,
      merkleProofService,
      initializationParams,
      missingCertificatesResolver
    )
  }
  //scalastyle:on

  def epochCalculator(
      sidechainEpochDerivation: SidechainEpochDerivation[IO],
      mainchainEpochDerivation: MainchainEpochDerivation
  ): EpochCalculator[IO] = new EpochCalculator[IO] {
    override def getSidechainEpoch(slot: Slot): SidechainEpoch =
      sidechainEpochDerivation.getSidechainEpoch(slot)

    override def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot =
      mainchainEpochDerivation.getFirstMainchainSlot(sidechainEpoch)

    override def getMainchainSlot(ts: SystemTime.UnixTimestamp): MainchainSlot =
      mainchainEpochDerivation.getMainchainSlot(ts)

    override def getCurrentEpoch: IO[SidechainEpoch] = IO.pure(SidechainEpoch(1))

    override def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds =
      mainchainEpochDerivation.getMainchainEpochStartTime(epoch)

    override def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds =
      sidechainEpochDerivation.getSidechainEpochStartTime(epoch)

    override def getSidechainEpochPhase(slot: Slot): EpochPhase =
      sidechainEpochDerivation.getSidechainEpochPhase(slot)

    override def getFirstSidechainEpoch(mainchainEpoch: MainchainEpoch): SidechainEpoch = {
      val startTime = mainchainEpochDerivation.getMainchainEpochStartTime(mainchainEpoch)
      val sidechainEpoch = sidechainEpochDerivation
        .getSidechainEpoch(UnixTimestamp.fromSeconds(startTime.seconds.toLong))
        .getOrElse(SidechainEpoch(0))
      val mainchainEpochFromEpochSidechain =
        mainchainEpochDerivation.getMainchainEpoch(sidechainEpoch)
      // The if/else is only needed to handle corner cases when the epoch are not aligned
      if (mainchainEpochFromEpochSidechain != mainchainEpoch)
        sidechainEpoch.next
      else
        sidechainEpoch
    }
  }
}
