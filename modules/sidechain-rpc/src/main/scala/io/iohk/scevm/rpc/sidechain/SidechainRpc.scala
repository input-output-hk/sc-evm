package io.iohk.scevm.rpc.sidechain

import cats.Monad
import cats.effect.Async
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain.{EpochPhase, Slot}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.rpc.JsonRpcConfig
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import io.iohk.scevm.rpc.sidechain.SidechainController.InitializationParams
import io.iohk.scevm.sidechain
import io.iohk.scevm.sidechain.{
  PendingTransactionsServiceImpl,
  SidechainBlockchainConfig,
  SidechainEpoch,
  SidechainModule
}
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainEpoch, MainchainSlot}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}

object SidechainRpc {
  //scalastyle:off parameter.number
  def createSidechainRpcRoutes[F[_]: Async: SystemTime, SignatureScheme <: AbstractSignatureScheme](
      jsonRpcConfig: JsonRpcConfig,
      blockProvider: BlockProvider[F],
      sidechainModule: SidechainModule[F, SignatureScheme],
      sidechainBlockchainConfig: SidechainBlockchainConfig,
      worldStateBuilder: WorldStateBuilder[F]
  ): RpcRoutes[F] = {

    val sidechainController = new SidechainControllerImpl[F, SignatureScheme](
      sidechainModule,
      sidechainModule,
      epochCalculator(sidechainModule),
      sidechainModule,
      sidechainModule.validateCandidate(_),
      blockProvider,
      sidechainModule.getSignatures(_),
      sidechainModule.getMerkleProof(_, _),
      InitializationParams(
        sidechainParams = sidechainBlockchainConfig.sidechainParams,
        initializationConfig = sidechainBlockchainConfig.initializationConfig
      ),
      limit => sidechainModule.getMissingCertificates(limit)
    )
    val crossChainTransactionController = new CrossChainTransactionController[F](
      epochCalculator(sidechainModule),
      new PendingTransactionsServiceImpl[F](
        sidechainModule,
        blockProvider,
        worldStateBuilder
      ),
      sidechainModule.getOutgoingTransaction(_)
    )

    SidechainDispatcher.handleSidechainRequests(jsonRpcConfig, sidechainController, crossChainTransactionController)
  }
  //scalastyle:on parameter.number
  private def epochCalculator[F[_]: Monad, SignatureScheme <: AbstractSignatureScheme](
      sidechainModule: SidechainModule[F, SignatureScheme]
  ) =
    new EpochCalculator[F] {
      override def getSidechainEpoch(slot: Slot): sidechain.SidechainEpoch =
        sidechainModule.getSidechainEpoch(slot)

      override def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot =
        sidechainModule.getFirstMainchainSlot(sidechainEpoch)

      override def getMainchainSlot(ts: SystemTime.UnixTimestamp): MainchainSlot =
        sidechainModule.getMainchainSlot(ts)

      override def getCurrentEpoch: F[sidechain.SidechainEpoch] =
        sidechainModule.getCurrentEpoch

      override def getSidechainEpochPhase(slot: Slot): EpochPhase = sidechainModule.getSidechainEpochPhase(slot)

      override def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds =
        sidechainModule.getMainchainEpochStartTime(epoch)

      override def getSidechainEpochStartTime(epoch: sidechain.SidechainEpoch): TimestampSeconds =
        sidechainModule.getSidechainEpochStartTime(epoch)

      override def getFirstSidechainEpoch(mainchainEpoch: MainchainEpoch): SidechainEpoch = {
        val startTime = sidechainModule.getMainchainEpochStartTime(mainchainEpoch)
        val sidechainEpoch = sidechainModule
          .getSidechainEpoch(UnixTimestamp.fromSeconds(startTime.seconds.toLong))
          .getOrElse(SidechainEpoch(0))
        val mainchainEpochFromEpochSidechain =
          sidechainModule.getMainchainEpoch(sidechainEpoch)
        // The if/else is only needed to handle corner cases when the epoch are not aligned
        if (mainchainEpochFromEpochSidechain != mainchainEpoch)
          sidechainEpoch.next
        else
          sidechainEpoch
      }

    }
}
