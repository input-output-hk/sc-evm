package io.iohk.scevm.ledger

import cats.effect.MonadCancelThrow
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.domain.BlocksBranch
import io.iohk.scevm.consensus.metrics.BlockMetrics
import io.iohk.scevm.consensus.pos.{
  BlockPreExecution,
  BranchExecution,
  BranchExecutionImpl,
  MonitoredObftBlockExecution,
  ObftBlockExecution,
  ObftBlockExecutionImpl
}
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.db.storage.{BranchProvider, ObftStorageBuilder}
import io.iohk.scevm.domain.{BlockContext, ObftHeader, SignedTransaction}
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.exec.vm.{StorageType, VM, WorldType}
import org.typelevel.log4cats.LoggerFactory

import BlockRewarder.BlockRewarder

trait Ledger[F[_]] extends BlockPreparation[F] with BranchExecution[F]

object Ledger {
  //scalastyle:off method.length
  def build[F[_]: LoggerFactory: MonadCancelThrow](
      blockchainConfig: BlockchainConfig
  )(
      blockPreExecution: BlockPreExecution[F],
      branchProvider: BranchProvider[F],
      postExecutionValidator: PostExecutionValidator[F],
      storages: ObftStorageBuilder[F],
      vm: VM[F, WorldType, StorageType],
      blockMetrics: BlockMetrics[F],
      worldStateBuilder: WorldStateBuilder[F]
  ): Ledger[F] = {

    val rewardAddressProvider =
      RewardAddressProvider(blockchainConfig.monetaryPolicyConfig.rewardAddress)

    val blockRewardCalculator =
      BlockRewardCalculator(blockchainConfig.monetaryPolicyConfig.blockReward, rewardAddressProvider)

    val blockRewarder: BlockRewarder[F] =
      BlockRewarder.payBlockReward[F](blockchainConfig, blockRewardCalculator)

    val transactionExecution =
      new TransactionExecutionImpl(
        vm,
        SignedTransactionValidatorImpl,
        rewardAddressProvider,
        blockchainConfig
      )

    val blockPreparation =
      new BlockPreparationImpl[F](
        blockPreExecution,
        transactionExecution,
        blockRewarder,
        worldStateBuilder
      )

    val blockExecution =
      new MonitoredObftBlockExecution[F](
        new ObftBlockExecutionImpl[F](
          blockPreExecution,
          transactionExecution,
          postExecutionValidator,
          blockRewarder,
          blockMetrics.executedBlocksCounter
        ),
        blockMetrics
      )

    val branchExecution =
      new BranchExecutionImpl[F](
        blockExecution,
        storages.blockchainStorage,
        branchProvider,
        storages.receiptStorage,
        worldStateBuilder
      )

    new LedgerImpl[F](blockPreparation, branchExecution)
  }
  //scalastyle:on method.length

  private class LedgerImpl[F[_]](
      blockPreparation: BlockPreparation[F],
      branchExecution: BranchExecution[F]
  ) extends Ledger[F] {

    override def prepareBlock(
        blockContext: BlockContext,
        transactionList: Seq[SignedTransaction],
        parent: ObftHeader
    ): F[PreparedBlock] =
      blockPreparation.prepareBlock(blockContext, transactionList, parent)

    override def execute(branch: BlocksBranch): F[Either[ObftBlockExecution.BlockExecutionError, BlocksBranch]] =
      branchExecution.execute(branch)

    override def executeRange(from: ObftHeader, to: ObftHeader): F[Either[BranchExecution.RangeExecutionError, Unit]] =
      branchExecution.executeRange(from, to)
  }

}
