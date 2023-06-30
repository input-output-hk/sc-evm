package io.iohk.scevm.sidechain

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import io.iohk.bytes.FromBytes
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource
import io.iohk.scevm.cardanofollower.datasource.dbsync.Db
import io.iohk.scevm.cardanofollower.datasource.mock.{MockedMainchainActiveFlowDataSource, MockedMainchainDataSource}
import io.iohk.scevm.cardanofollower.datasource.{
  DataSource,
  DatasourceConfig,
  IncomingTransactionDataSource,
  MainchainActiveFlowDataSource,
  MainchainDataSource,
  ValidEpochData
}
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.{BlockPreExecution, CurrentBranch, PoSConfig}
import io.iohk.scevm.domain.{BlockContext, SignedTransaction, Tick}
import io.iohk.scevm.exec.vm.{EvmCall, StorageType, TransactionSimulator, VM, WorldType}
import io.iohk.scevm.ledger.NonceProviderImpl
import io.iohk.scevm.sidechain.TtlCache.CacheOperation
import io.iohk.scevm.sidechain.certificate.{CommitteeHandoverSigner, MissingCertificatesResolver}
import io.iohk.scevm.sidechain.committee._
import io.iohk.scevm.sidechain.consensus.SidechainPostExecutionValidator
import io.iohk.scevm.sidechain.metrics.{PrometheusTtlCacheMetrics, SidechainMetrics}
import io.iohk.scevm.sidechain.observability.{HandoverLagObserver, MainchainSlotLagObserver, ObserverScheduler}
import io.iohk.scevm.sidechain.pos.SidechainBlockPreExecution
import io.iohk.scevm.sidechain.transactions.{MerkleProofProviderImpl, MerkleProofService, OutgoingTransactionService}
import io.iohk.scevm.storage.execution.SlotBasedMempool
import io.iohk.scevm.sync.NextBlockTransactions
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.MainchainEpoch
import io.iohk.scevm.utils.SystemTime
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jLogger

// scalastyle:off method.length parameter.number
object SidechainModuleBuilder {

  def createSidechainModule[
      F[_]: Trace: LoggerFactory: SystemTime: Async: CurrentBranch.Signal,
      SignatureScheme <: AbstractSignatureScheme
  ](
      blockchainConfig: SidechainBlockchainConfig,
      extraSidechainConfig: ExtraSidechainConfig,
      poSConfig: PoSConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      vm: VM[F, WorldType, StorageType],
      metrics: SidechainMetrics[F],
      worldStateBuilder: WorldStateBuilder[F],
      ticker: fs2.Stream[F, Tick]
  )(implicit
      signatureFromBytes: FromBytes[SignatureScheme#Signature]
  ): Resource[F, SidechainModule[F, SignatureScheme]] = {
    val sidechainEpochDerivation = new SidechainEpochDerivationImpl[F](blockchainConfig.obftConfig)
    val preExecution = new SidechainBlockPreExecution[F](
      blockchainConfig.bridgeContractAddress,
      sidechainEpochDerivation
    )
    val filteredTicker =
      PreventStartInHandoverTicker(ticker, implicitly[CurrentBranch.Signal[F]], sidechainEpochDerivation)

    extraSidechainConfig.datasource match {
      case dbSyncConfig: DatasourceConfig.DbSync =>
        dbSyncBasedSidechainModule[F, SignatureScheme](
          blockchainConfig = blockchainConfig,
          extraSidechainConfig = extraSidechainConfig,
          dbSyncConfig = dbSyncConfig,
          posConfig = poSConfig,
          mempool = mempool,
          transactionSimulator = TransactionSimulator[F](blockchainConfig, vm, preExecution.prepare),
          worldStateBuilder = worldStateBuilder,
          metrics = metrics,
          preExecution = preExecution,
          ticker = filteredTicker
        )

      case dbSyncConfig: DatasourceConfig.Mock =>
        Resource.eval(LoggerFactory.getLogger.info("Running with mocked db-sync")) >>
          mockedDbSyncBasedSidechainModule[F, SignatureScheme](
            blockchainConfig = blockchainConfig,
            extraSidechainConfig = extraSidechainConfig,
            dbSyncConfig = dbSyncConfig,
            posConfig = poSConfig,
            mempool = mempool,
            transactionSimulator = TransactionSimulator[F](blockchainConfig, vm, preExecution.prepare),
            worldStateBuilder = worldStateBuilder,
            metrics = metrics,
            preExecution = preExecution,
            ticker = filteredTicker
          )
    }
  }

  private def dbSyncBasedSidechainModule[
      F[_]: Trace: LoggerFactory: SystemTime: Async: CurrentBranch.Signal,
      SignatureScheme <: AbstractSignatureScheme
  ](
      blockchainConfig: SidechainBlockchainConfig,
      extraSidechainConfig: ExtraSidechainConfig,
      dbSyncConfig: DatasourceConfig.DbSync,
      posConfig: PoSConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      transactionSimulator: TransactionSimulator[EvmCall[F, *]],
      worldStateBuilder: WorldStateBuilder[F],
      metrics: SidechainMetrics[F],
      preExecution: BlockPreExecution[F],
      ticker: fs2.Stream[F, Tick]
  )(implicit
      signatureFromBytes: FromBytes[SignatureScheme#Signature]
  ): Resource[F, SidechainModule[F, SignatureScheme]] = {
    val db = new Db(dbSyncConfig)
    for {
      xa       <- db.transactor[F]
      mainnetDs = DataSource.forDbSync[F, SignatureScheme](blockchainConfig.mainchainConfig, xa)
      module <- sidechainModule[F, SignatureScheme](
                  blockchainConfig,
                  extraSidechainConfig,
                  mainnetDs,
                  mainnetDs,
                  posConfig,
                  mempool,
                  worldStateBuilder,
                  metrics,
                  transactionSimulator,
                  preExecution,
                  mainnetDs,
                  ticker
                )
    } yield module
  }

  private def mockedDbSyncBasedSidechainModule[
      F[_]: Trace: LoggerFactory: SystemTime: Async: CurrentBranch.Signal,
      SignatureScheme <: AbstractSignatureScheme
  ](
      blockchainConfig: SidechainBlockchainConfig,
      extraSidechainConfig: ExtraSidechainConfig,
      dbSyncConfig: DatasourceConfig.Mock,
      posConfig: PoSConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      transactionSimulator: TransactionSimulator[EvmCall[F, *]],
      worldStateBuilder: WorldStateBuilder[F],
      metrics: SidechainMetrics[F],
      preExecution: BlockPreExecution[F],
      ticker: fs2.Stream[F, Tick]
  )(implicit
      signatureFromBytes: FromBytes[SignatureScheme#Signature]
  ): Resource[F, SidechainModule[F, SignatureScheme]] = {
    val simulationDuration = dbSyncConfig.numberOfSlots * blockchainConfig.slotDuration
    val numberOfMainchainSlot =
      (simulationDuration / blockchainConfig.mainchainConfig.slotDuration).toInt
    val mainnetDs = new MockedMainchainDataSource[F, SignatureScheme](
      blockchainConfig.sidechainParams,
      dbSyncConfig,
      numberOfMainchainSlot
    )

    sidechainModule[F, SignatureScheme](
      blockchainConfig,
      extraSidechainConfig,
      mainnetDs,
      mainnetDs,
      posConfig,
      mempool,
      worldStateBuilder,
      metrics,
      transactionSimulator,
      preExecution,
      activeFlowDataProvider = new MockedMainchainActiveFlowDataSource(),
      ticker = ticker
    )
  }

  private def sidechainModule[
      F[_]: Trace: LoggerFactory: SystemTime: Async: CurrentBranch.Signal,
      SignatureScheme <: AbstractSignatureScheme
  ](
      blockchainConfig: SidechainBlockchainConfig,
      extraSidechainConfig: ExtraSidechainConfig,
      mainnetDs: MainchainDataSource[F, SignatureScheme],
      incomingDs: IncomingTransactionDataSource[F],
      posConfig: PoSConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      worldStateBuilder: WorldStateBuilder[F],
      metrics: SidechainMetrics[F],
      transactionSimulator: TransactionSimulator[EvmCall[F, *]],
      preExecution: BlockPreExecution[F],
      activeFlowDataProvider: MainchainActiveFlowDataSource[F],
      ticker: fs2.Stream[F, Tick]
  )(implicit
      signatureFromBytes: FromBytes[SignatureScheme#Signature]
  ): Resource[F, SidechainModule[F, SignatureScheme]] = {
    val bridgeContract =
      new BridgeContract[EvmCall[F, *], SignatureScheme](
        blockchainConfig.chainId,
        blockchainConfig.bridgeContractAddress,
        transactionSimulator
      )
    val mcSlotDerivation =
      new MainchainSlotDerivationImpl(blockchainConfig.mainchainConfig, blockchainConfig.slotDuration)
    val sidechainParams = blockchainConfig.sidechainParams

    val candidateValidator = {
      val baseValidator =
        new CandidateRegistrationValidatorImpl[F, SignatureScheme](
          sidechainParams,
          blockchainConfig.minimalCandidateStake
        )
      blockchainConfig.committeeWhitelist match {
        case Some(whitelist) =>
          new WhitelistedCandidateRegistrationValidator[F, SignatureScheme](baseValidator, whitelist.toList.toSet)
        case None => baseValidator
      }
    }
    val incomingTransactionsProvider = createIncomingTxProvider(incomingDs, bridgeContract, mcSlotDerivation)
    for {
      cachedMainnetDs <-
        cacheEpochDataResults(mainnetDs, candidateValidator, extraSidechainConfig.electionCache)
      sidechainEpochDerivation = new SidechainEpochDerivationImpl(blockchainConfig.obftConfig)
      mainchainEpochDerivation =
        new MainchainEpochDerivationImpl(blockchainConfig.obftConfig, blockchainConfig.mainchainConfig)
      committeeProvider =
        createCommitteeProvider(cachedMainnetDs, blockchainConfig, mainchainEpochDerivation)
      getCommittee = epoch => committeeProvider.getCommittee(epoch)
      tokenConversionRate <-
        Resource.eval(
          Resource
            .eval(CurrentBranch.best[F])
            .flatMap(best => worldStateBuilder.getWorldStateForBlock(best.stateRoot, BlockContext.from(best)))
            .use(bridgeContract.getConversionRate.run)
        )
      validator = SidechainPostExecutionValidator(
                    incomingTransactionsProvider,
                    blockchainConfig,
                    bridgeContract,
                    sidechainEpochDerivation,
                    getCommittee,
                    tokenConversionRate
                  )
      nextTransactionProvider = createNextBlockTransactions(
                                  posConfig,
                                  mempool,
                                  blockchainConfig,
                                  worldStateBuilder,
                                  bridgeContract,
                                  incomingTransactionsProvider,
                                  metrics,
                                  sidechainEpochDerivation,
                                  committeeProvider,
                                  sidechainParams
                                )
      crossChainSignaturesService = new CrossChainSignaturesService[F, SignatureScheme](
                                      sidechainParams,
                                      bridgeContract,
                                      committeeProvider,
                                      worldStateBuilder
                                    )
      outgoingTransactionService = new OutgoingTransactionService[F](bridgeContract, worldStateBuilder)
      merkleProofService         = new MerkleProofService[F](new MerkleProofProviderImpl[F](bridgeContract), worldStateBuilder)
      missingCertificateResolver = MissingCertificatesResolver[F, SignatureScheme](
                                     sidechainEpochDerivation,
                                     activeFlowDataProvider,
                                     bridgeContract,
                                     worldStateBuilder
                                   )
      _ <- ObserverScheduler.start(
             List(
               new HandoverLagObserver[F, SignatureScheme](
                 activeFlowDataProvider,
                 metrics,
                 sidechainEpochDerivation,
                 committeeProvider,
                 extraSidechainConfig.observabilityConfig
               ),
               new MainchainSlotLagObserver[F, SignatureScheme](
                 mainchainEpochDerivation,
                 mainnetDs,
                 metrics,
                 extraSidechainConfig.observabilityConfig,
                 blockchainConfig.mainchainConfig
               )
             ),
             extraSidechainConfig.observabilityConfig
           )

      monitoredTicker =
        ticker.evalTap { tick =>
          val currentEpochNumber         = sidechainEpochDerivation.getSidechainEpoch(tick.slot).number
          val previousSlot               = tick.slot.copy(number = tick.slot.number - 1)
          val epochNumberForPreviousSlot = sidechainEpochDerivation.getSidechainEpoch(previousSlot).number
          LoggerFactory.getLogger
            .info(show"Starting new sidechain epoch $currentEpochNumber")
            .whenA(currentEpochNumber != epochNumberForPreviousSlot) >>
            metrics.epochGauge.set(currentEpochNumber.toDouble) >>
            metrics.epochPhaseGauge.set(1, List(sidechainEpochDerivation.getSidechainEpochPhase(tick.slot).raw))
        }
    } yield SidechainModule.withSidechain(
      committeeProvider,
      incomingTransactionsProvider,
      nextTransactionProvider,
      sidechainEpochDerivation,
      mainchainEpochDerivation,
      mainnetDs,
      candidateValidator,
      crossChainSignaturesService,
      transactionSimulator,
      outgoingTransactionService,
      validator,
      preExecution,
      merkleProofService,
      missingCertificateResolver,
      monitoredTicker
    )
  }

  private def createIncomingTxProvider[F[_]: Sync: Trace](
      mainnetDs: IncomingTransactionDataSource[F],
      bridgeContract: BridgeContract[EvmCall[F, *], _],
      mcSlotDerivation: MainchainSlotDerivation
  ): IncomingCrossChainTransactionsProviderImpl[F] =
    new IncomingCrossChainTransactionsProviderImpl[F](
      bridgeContract,
      mainnetDs.getNewTransactions(_, _),
      mainnetDs.getUnstableTransactions(_, _),
      mcSlotDerivation
    )

  private def createCommitteeProvider[F[_]: Sync: Trace, Scheme <: AbstractSignatureScheme](
      cachedMainnetDs: TtlCache[F, MainchainEpoch, Option[ValidEpochData[Scheme]]],
      blockchainConfig: SidechainBlockchainConfig,
      mainchainEpochDerivation: MainchainEpochDerivation
  ): SidechainCommitteeProvider[F, Scheme] = {
    val committeeProviderLogger = Slf4jLogger.getLoggerFromClass[F](SidechainCommitteeProvider.getClass)
    new SidechainCommitteeProviderImpl[F, Scheme](
      mainchainEpochDerivation,
      (epoch: MainchainEpoch) => cachedMainnetDs.apply(epoch)(committeeProviderLogger),
      blockchainConfig.committeeConfig
    )
  }

  private def createNextBlockTransactions[
      F[_]: Trace: Async: LoggerFactory,
      SignatureScheme <: AbstractSignatureScheme
  ](
      posConfig: PoSConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      blockchainConfig: SidechainBlockchainConfig,
      worldStateBuilder: WorldStateBuilder[F],
      bridgeContract: BridgeContract[EvmCall[F, *], SignatureScheme],
      transactionsProvider: IncomingCrossChainTransactionsProvider[F],
      metrics: SidechainMetrics[F],
      epochDerivation: SidechainEpochDerivation[F],
      committeeProvider: SidechainCommitteeProvider[F, SignatureScheme],
      sidechainParams: SidechainParams
  ): NextBlockTransactions[F] = {
    val mempoolAdapter = NewBlockTransactionsProvider.SlotBasedMempool(mempool)
    NonEmptyList.fromList(posConfig.validatorPrivateKeys) match {
      case Some(_) =>
        val nonceProvider = new NonceProviderImpl(getAllFromMemPool = mempool.getAll, blockchainConfig)
        val incomingTransactionsService = new IncomingTransactionsServiceImpl[F](
          transactionsProvider,
          bridgeContract
        )

        val signTransactionProvider =
          new HandoverTransactionProviderImpl[F, SignatureScheme](
            nonceProvider,
            committeeProvider,
            bridgeContract,
            sidechainParams,
            new CommitteeHandoverSigner[F]
          )
        NewBlockTransactionsProvider.validator(
          blockchainConfig,
          mempoolAdapter,
          worldStateBuilder,
          signTransactionProvider,
          incomingTransactionsService,
          metrics,
          epochDerivation
        )
      case None => NewBlockTransactionsProvider.passive(mempoolAdapter)
    }
  }

  private def cacheEpochDataResults[F[_]: Async, SignatureScheme <: AbstractSignatureScheme](
      mainchainDataSource: MainchainDataSource[F, SignatureScheme],
      candidateValidator: CandidateRegistrationValidator[F, SignatureScheme],
      electionCache: ElectionCacheConfig
  ): Resource[F, TtlCache[F, MainchainEpoch, Option[ValidEpochData[SignatureScheme]]]] =
    PrometheusTtlCacheMetrics[F]("get_mainchain_data").evalMap { cacheMetrics =>
      TtlCache.wrap[F, MainchainEpoch, Option[ValidEpochData[SignatureScheme]]](
        electionCache.cacheSize,
        cacheMetrics,
        validEpochDataProvider(mainchainDataSource, candidateValidator),
        {
          case CacheOperation.Create(None)    => electionCache.invalidData.initial
          case CacheOperation.Create(Some(_)) => electionCache.validData.initial
          case CacheOperation.Read(Some(_))   => electionCache.validData.refresh
          case CacheOperation.Read(None)      => electionCache.invalidData.refresh
        }
      )
    }

  private def validEpochDataProvider[F[_]: Async, SignatureScheme <: AbstractSignatureScheme](
      mainchainDataSource: MainchainDataSource[F, SignatureScheme],
      candidateValidator: CandidateRegistrationValidator[F, SignatureScheme]
  ): MainchainEpoch => F[Option[ValidEpochData[SignatureScheme]]] = { mainchainEpoch =>
    (for {
      epochData       <- OptionT(mainchainDataSource.getEpochData(mainchainEpoch))
      validCandidates <- OptionT.liftF(candidateValidator.validate(epochData.candidates))
    } yield datasource.ValidEpochData(epochData.previousEpochNonce, validCandidates)).value
  }
}
