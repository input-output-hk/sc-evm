package io.iohk.dataGenerator.services

import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.typesafe.config.Config
import fs2.io.file.Path
import io.iohk.dataGenerator.domain.Validator
import io.iohk.dataGenerator.services.transactions.ContractCallTransactionGenerator
import io.iohk.scevm.config.StandaloneBlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.consensus.metrics.{NoOpBlockMetrics, NoOpConsensusMetrics, TimeToFinalizationTracker}
import io.iohk.scevm.consensus.pos.CurrentBranchService
import io.iohk.scevm.db.DataSourceConfig.RocksDbConfig
import io.iohk.scevm.db.storage.GetByNumberServiceImpl
import io.iohk.scevm.domain.{BlockHash, ObftBlock}
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.exec.vm.{StorageType, WorldType}
import io.iohk.scevm.extvm.{VMSetup, VmConfig}
import io.iohk.scevm.ledger.BlockRewarder.BlockRewarder
import io.iohk.scevm.ledger.{
  BlockRewardCalculator,
  BlockRewarder,
  LeaderElection,
  NonceProviderImpl,
  RewardAddressProvider,
  RoundRobinLeaderElection,
  SlotDerivation,
  TransactionExecutionImpl
}
import io.iohk.scevm.network.domain.ChainDensity
import io.iohk.scevm.node.Phase0
import io.iohk.scevm.node.Phase0.Phase0Outcome
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import scala.concurrent.duration.FiniteDuration

class ChainGenerator private (dbPath: Path)(
    phaseGeneration: PhaseGeneration,
    phaseImport: PhaseImport,
    phaseConsensus: PhaseConsensus
)(implicit loggerFactory: LoggerFactory[IO]) {
  private val logger: SelfAwareStructuredLogger[IO] = loggerFactory.getLogger

  def run(
      name: String,
      startingBlock: ObftBlock,
      length: Int,
      participating: IndexedSeq[Validator]
  ): IO[ObftBlock] = for {
    _                         <- IO.unit
    destinationName            = s"${name}_$length-blocks"
    generatedBlocks            = phaseGeneration.run(startingBlock, length, participating)
    importedBlocks             = phaseImport.run(generatedBlocks)
    (stable, best, unstables) <- phaseConsensus.run(importedBlocks)
    _                         <- PhaseDBDump.dumpDbFiles(dbPath, destinationName)
    // happens after DB dump for the consistency of stable mappings in following runs
    _ <- phaseConsensus.stabilize(unstables)

    density = ChainDensity.density(best.header, startingBlock.header)
    _ <-
      logger.info(
        show"Generated chain=$destinationName, from=$startingBlock, to=$best, density=$density, stable=$stable"
      )
  } yield best

}

object ChainGenerator {
  // scalastyle:off method.length
  def build(
      scEvmConfig: Config,
      validators: IndexedSeq[Validator]
  )(implicit loggerFactory: LoggerFactory[IO]): Resource[IO, (ChainGenerator, ObftBlock)] =
    for {
      _               <- Resource.unit
      vmConfig         = VmConfig.fromRawConfig(scEvmConfig)
      rockDbConfig     = RocksDbConfig.fromConfig(scEvmConfig.getConfig("db").getConfig("rocksdb"))
      blockchainConfig = StandaloneBlockchainConfig.fromRawConfig(scEvmConfig.getConfig("blockchain"))
      Phase0Outcome(genesis, storages) <- Phase0
                                            .run[IO](
                                              blockchainConfig = blockchainConfig,
                                              dbConfig = rockDbConfig,
                                              storageMetrics = NoOpStorageMetrics[IO]()
                                            )

      blockRewarder: BlockRewarder[IO] = {
        val rewardAddressProvider: RewardAddressProvider = RewardAddressProvider(
          blockchainConfig.monetaryPolicyConfig.rewardAddress
        )
        val blockRewardCalculator: BlockRewardCalculator =
          BlockRewardCalculator(blockchainConfig.monetaryPolicyConfig.blockReward, rewardAddressProvider)
        BlockRewarder.payBlockReward[IO](blockchainConfig, blockRewardCalculator)
      }
      vm <- VMSetup.vm[WorldType, StorageType](vmConfig, blockchainConfig)
      transactionExecution = {
        val rewardAddressProvider = RewardAddressProvider(
          blockchainConfig.monetaryPolicyConfig.rewardAddress
        )
        new TransactionExecutionImpl(vm, SignedTransactionValidatorImpl, rewardAddressProvider, blockchainConfig)
      }
      worldStateBuilder = new WorldStateBuilderImpl[IO](
                            storages.evmCodeStorage,
                            new GetByNumberServiceImpl[IO](
                              storages.blockchainStorage,
                              storages.stableNumberMappingStorage
                            ),
                            storages.stateStorage,
                            blockchainConfig
                          )
      leaderElection: LeaderElection[IO] = new RoundRobinLeaderElection[IO](validators.map(_.publicKey))
      slotDerivation                     = SlotDerivation.live(genesis.header.unixTimestamp, blockchainConfig.slotDuration).toOption.get
      phaseGeneration <- PhaseGeneration.build(blockchainConfig)(
                           leaderElection,
                           blockRewarder,
                           storages.receiptStorage,
                           transactionExecution, {
                             val nonceProvider = new NonceProviderImpl[IO](IO.pure(List.empty), blockchainConfig)
                             new ContractCallTransactionGenerator(
                               blockchainConfig.chainId,
                               nonceProvider,
                               worldStateBuilder
                             )
                           },
                           worldStateBuilder
                         )
      phaseImport <- PhaseImport.build(genesis.header)(leaderElection, slotDerivation, storages)
      currentBranchService <- Resource.eval(
                                CurrentBranchService.init(
                                  storages.appStorage,
                                  storages.blockchainStorage,
                                  storages.stableNumberMappingStorage,
                                  storages.transactionMappingStorage,
                                  genesis.header,
                                  NoOpBlockMetrics[IO](),
                                  NoOpStorageMetrics[IO](),
                                  new TimeToFinalizationTracker[IO] {
                                    override def track(blockHash: BlockHash): IO[Unit] = IO.unit
                                    override def evaluate(blockHash: BlockHash): IO[Option[FiniteDuration]] =
                                      IO.pure(None)
                                  },
                                  NoOpConsensusMetrics[IO]()
                                )
                              )
      phaseConsensus <- PhaseConsensus.build(blockchainConfig)(currentBranchService)
    } yield (
      new ChainGenerator(Path.fromNioPath(rockDbConfig.path))(phaseGeneration, phaseImport, phaseConsensus),
      genesis
    )
  // scalastyle:on method.length

}
