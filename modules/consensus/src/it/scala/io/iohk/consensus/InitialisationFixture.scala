package io.iohk.consensus

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.consensus.metrics.{NoOpBlockMetrics, NoOpConsensusMetrics, TimeToFinalizationTracker}
import io.iohk.scevm.consensus.pos.{
  BlockPreExecution,
  Consensus,
  ConsensusBuilderImpl,
  ConsensusService,
  CurrentBranch,
  CurrentBranchService
}
import io.iohk.scevm.consensus.validators.{HeaderValidator, HeaderValidatorImpl, PostExecutionValidatorImpl}
import io.iohk.scevm.db.dataSource.RocksDbDataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.db.storage.genesis.ObftGenesisValidation
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.exec.vm.{EVM, StorageType, WorldType}
import io.iohk.scevm.ledger.{
  BlockImportService,
  BlockImportServiceImpl,
  BlockProvider,
  BlockProviderImpl,
  LeaderElection,
  RoundRobinLeaderElection,
  SlotDerivation,
  _
}
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.fixtures.GenesisBlock
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

object InitialisationFixture {
  implicit val loggerFactory: LoggerFactory[IO] = NoOpFactory[IO]
  /* Initialisation methods */

  final case class Fixture(
      generator: ChainGenerator,
      importService: BlockImportService[IO],
      consensusService: ConsensusService[IO],
      blocksReader: BlocksReader[IO],
      consensusInit: fs2.Stream[IO, ObftBlock],
      currentBranchService: CurrentBranchService[IO],
      blockProvider: BlockProvider[IO],
      stableNumberMappingStorage: StableNumberMappingStorage[IO]
  )(implicit val currentBranch: CurrentBranch.Signal[IO])

  // scalastyle:off method.length
  def init(
      dataSource: RocksDbDataSource,
      blockchainConfig: BlockchainConfig,
      validators: Vector[ECDSA.PublicKey]
  ): IO[Fixture] = {
    lazy val leaderElection: LeaderElection[IO] = new RoundRobinLeaderElection[IO](validators)

    lazy val slotDerivation =
      SlotDerivation.live(GenesisBlock.block.header.unixTimestamp, blockchainConfig.slotDuration).toOption.get

    lazy val headerValidator: HeaderValidator[IO] =
      new HeaderValidatorImpl(leaderElection, GenesisBlock.block.header, slotDerivation)

    lazy val generator =
      new ChainGenerator(
        leaderElection,
        slotDerivation,
        blockchainConfig.slotDuration,
        ConfigFixture.pubPrvKeyMapping
      )

    lazy val vm = EVM[WorldType, StorageType]()

    val storageMetrics = NoOpStorageMetrics[IO]()

    for {
      storageBuilder <- ObftStorageBuilder[IO](dataSource, storageMetrics)
      _ <- ObftGenesisValidation.initGenesis(
             storageBuilder.stableNumberMappingStorage.getBlockHash,
             storageBuilder.blockchainStorage,
             storageBuilder.blockchainStorage,
             storageBuilder.receiptStorage
           )(
             GenesisBlock.block
           )
      _ <- StableNumberMappingStorage.init[IO](storageBuilder.stableNumberMappingStorage)(
             GenesisBlock.block
           )
      noOpBlockMetrics     = NoOpBlockMetrics[IO]()
      noOpConsensusMetrics = NoOpConsensusMetrics[IO]()
      currentBranchService <-
        CurrentBranchService.init[IO](
          storageBuilder.appStorage,
          storageBuilder.blockchainStorage,
          storageBuilder.stableNumberMappingStorage,
          storageBuilder.transactionMappingStorage,
          GenesisBlock.header,
          noOpBlockMetrics,
          storageMetrics,
          TimeToFinalizationTracker.noop,
          noOpConsensusMetrics
        )
      implicit0(currentBranch: CurrentBranch.Signal[IO]) = currentBranchService.signal
      worldStateBuilder = new WorldStateBuilderImpl(
                            storageBuilder.evmCodeStorage,
                            new GetByNumberServiceImpl(
                              storageBuilder.blockchainStorage,
                              storageBuilder.stableNumberMappingStorage
                            ),
                            storageBuilder.stateStorage,
                            blockchainConfig
                          )
      ledger = Ledger.build[IO](blockchainConfig)(
                 BlockPreExecution.noop,
                 storageBuilder.blockchainStorage,
                 new PostExecutionValidatorImpl,
                 storageBuilder,
                 vm,
                 NoOpBlockMetrics[IO](),
                 worldStateBuilder
               )
      consensusBuilder = ConsensusBuilderImpl
                           .build[IO](blockchainConfig)(
                             ledger,
                             currentBranchService,
                             storageBuilder,
                             NoOpConsensusMetrics()
                           )
                           .unsafeRunSync()
      importerService =
        new BlockImportServiceImpl[IO](
          storageBuilder.blockchainStorage,
          storageBuilder.blockchainStorage,
          headerValidator
        )
      consensusInit =
        Consensus
          .init[IO](storageBuilder.blockchainStorage, storageBuilder.blockchainStorage, storageBuilder.appStorage)
      getByNumberService =
        new GetByNumberServiceImpl[IO](storageBuilder.blockchainStorage, storageBuilder.stableNumberMappingStorage)
    } yield Fixture(
      generator,
      importerService,
      consensusBuilder,
      storageBuilder.blockchainStorage,
      consensusInit,
      currentBranchService = currentBranchService,
      blockProvider = new BlockProviderImpl[IO](storageBuilder.blockchainStorage, getByNumberService),
      stableNumberMappingStorage = storageBuilder.stableNumberMappingStorage
    )
  }
  // scalastyle:on method.length
}
