package io.iohk.scevm.node

import cats.effect._
import com.typesafe.scalalogging.StrictLogging
import fs2.concurrent.SignallingRef
import io.iohk.bytes.ByteString
import io.iohk.scevm.ScEvmConfig
import io.iohk.scevm.config._
import io.iohk.scevm.consensus.metrics.{BlockMetrics, PrometheusConsensusMetrics, TimeToFinalizationTracker}
import io.iohk.scevm.consensus.pos.{ConsensusBuilder, ConsensusService, CurrentBranch, CurrentBranchService}
import io.iohk.scevm.consensus.validators.HeaderValidatorImpl
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.mempool.SignedTransactionMempool
import io.iohk.scevm.ledger._
import io.iohk.scevm.ledger.blockgeneration.MonitoredBlockGeneratorImp
import io.iohk.scevm.network._
import io.iohk.scevm.network.handshaker.ObftHandshakerConfig
import io.iohk.scevm.network.metrics.{
  ConsensusNetworkMetricsUpdater,
  ForkingFactorImpl,
  PrometheusBlockPropagationMetrics,
  PrometheusNetworkMetrics
}
import io.iohk.scevm.network.rlpx.AuthHandshaker
import io.iohk.scevm.node.blockproduction.ConsensusChainExtender
import io.iohk.scevm.rpc.armadillo.openrpc.OpenRpcDiscoveryService
import io.iohk.scevm.rpc.controllers.NetController.ChainMode
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import io.iohk.scevm.rpc.tapir.{BuildInfoEndpoint, HealthCheckService, OpenRpcDocumentationEndpoint}
import io.iohk.scevm.sidechain.{ChainModule, SidechainBlockchainConfig}
import io.iohk.scevm.utils._
import io.janstenpickle.trace4cats.inject.Trace
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.typelevel.log4cats.LoggerFactory

import java.security.SecureRandom

class Builder(
    nodeConfig: ScEvmConfig,
    nodeKey: AsymmetricCipherKeyPair,
    slotDerivation: SlotDerivation,
    val chainModule: ChainModule[IO],
    secureRandom: SecureRandom,
    obftStorageBuilder: ObftStorageBuilder[IO],
    val genesisHeader: ObftHeader,
    val consensusBuilder: ConsensusBuilder[IO],
    nodeStatusProvider: NodeStatusProvider[IO],
    blockMetrics: BlockMetrics[IO],
    consensusMetrics: PrometheusConsensusMetrics[IO],
    timeToFinalizationTracker: TimeToFinalizationTracker[IO],
    mempool: SignedTransactionMempool[IO],
    blockProvider: BlockProvider[IO],
    getByNumberService: GetByNumberService[IO],
    additionalRoutes: RpcRoutes[IO],
    ledger: Ledger[IO],
    currentBranchService: CurrentBranchService[IO]
)(implicit
    systemTime: SystemTime[IO],
    trace: Trace[IO],
    loggerFactory: LoggerFactory[IO]
) extends StrictLogging { builder =>

  // Core
  val appConfig: AppConfig = nodeConfig.appConfig
  val selfNodeId: NodeId   = NodeId.fromKey(nodeKey)

  // Sanitize the blockchain config by removing our own node id from the bootstrap nodes
  val networkConfig: NetworkConfig = {
    val originalConfig = nodeConfig.networkConfig

    logger.info("Network: {}", nodeConfig.networkName)
    originalConfig.copy(bootstrapNodes = originalConfig.bootstrapNodes.filterNot(_.getUserInfo == selfNodeId.toHex))
  }

  // Network
  private def createHandshakerConfiguration(
      genesis: ObftHeader,
      nodeStatusProvider: NodeStatusProvider[IO]
  )(implicit currentSignal: CurrentBranch.Signal[IO]): ObftHandshakerConfig[IO] =
    new ObftHandshakerConfig[IO] {
      def genesisHash: BlockHash                           = genesis.hash
      def nodeStatusHolder: NodeStatusProvider[IO]         = nodeStatusProvider
      def peerConfiguration: PeerConfig                    = nodeConfig.networkConfig.peer
      implicit def currentBranch: CurrentBranch.Signal[IO] = currentSignal
      def blockchainConfig: BlockchainConfig               = nodeConfig.blockchainConfig
      def appConfig: AppConfig                             = builder.appConfig
      def modeConfigHash: ByteString                       = builder.nodeConfig.blockchainConfig.nodeCompatibilityHash
    }

  // build IO
  // scalastyle:off method.length
  // scalastyle:off line.size.limit
  def startApp(
      killSignal: Deferred[IO, Either[Throwable, Unit]]
  ): Resource[IO, ScEvmNode[IO]] = for {
    _                                 <- Resource.eval(IO.unit)
    blockPropagationMetrics           <- PrometheusBlockPropagationMetrics[IO]
    blocksReader: BlocksReader[IO]     = obftStorageBuilder.blockchainStorage
    blocksWriter: BlocksWriter[IO]     = obftStorageBuilder.blockchainStorage
    branchProvider: BranchProvider[IO] = obftStorageBuilder.blockchainStorage
    implicit0(headerValidator: HeaderValidatorImpl[IO]) =
      new HeaderValidatorImpl[IO](chainModule.getSlotLeader(_), genesisHeader, slotDerivation)
    implicit0(currentBranch: CurrentBranch.Signal[IO]) = currentBranchService.signal
    networkMetrics                                    <- PrometheusNetworkMetrics[IO]
    handshakerConfiguration                            = createHandshakerConfiguration(genesisHeader, nodeStatusProvider)

    currentTime <- Resource.eval(SystemTime[IO].realTime())
    currentSlot = slotDerivation
                    .getSlot(currentTime)
                    .fold(reason => throw new Error(s"Error deriving current slot. Reason: $reason"), identity)

    currentSlotRef <- Resource.eval(SignallingRef.of[IO, Slot](currentSlot))
    networkStorage = new NetworkModuleStorageImpl[IO](
                       obftStorageBuilder.stateStorage,
                       obftStorageBuilder.evmCodeStorage,
                       obftStorageBuilder.receiptStorage,
                       blocksReader,
                       getByNumberService,
                       blocksWriter,
                       branchProvider
                     )
    networkModule <-
      new NetworkModuleBuilder(
        networkConfig,
        nodeConfig.syncConfig,
        nodeConfig.blockchainConfig,
        handshakerConfiguration,
        nodeConfig.branchFetcherConfig,
        selfNodeId,
        AuthHandshaker(nodeKey, secureRandom),
        killSignal,
        networkStorage,
        timeToFinalizationTracker
      )
        .build(networkMetrics, currentSlotRef, mempool, blockPropagationMetrics)
    obftBlockImportService: BlockImportService[IO] =
      new BlockImportServiceImpl(
        obftStorageBuilder.blockchainStorage,
        obftStorageBuilder.blockchainStorage,
        headerValidator
      )
    _ <- Resource.eval {
           implicit val ioToFuture: ToFuture[IO] = IoToFuture
           SynchronizationPhase.runBlocking(
             nodeConfig,
             networkModule,
             ledger,
             blockProvider,
             currentBranchService,
             nodeStatusProvider,
             branchProvider
           )
         }
    rpcRoutes   <- Resource.eval(createRoutes(branchProvider, currentBranch, networkModule))
    readySignal <- Resource.eval(Deferred[IO, Unit])

    forkingFactor                 <- Resource.eval(ForkingFactorImpl[IO](blockProvider, nodeConfig.blockchainConfig.stabilityParameter))
    consensusNetworkMetricsUpdater = ConsensusNetworkMetricsUpdater(networkMetrics, forkingFactor)

    chainExtender = new ConsensusChainExtender[IO](
                      new MonitoredBlockGeneratorImp[IO](ledger, blockMetrics),
                      chainModule,
                      obftBlockImportService,
                      consensusBuilder,
                      timeToFinalizationTracker,
                      blockPropagationMetrics,
                      consensusNetworkMetricsUpdater,
                      networkModule
                    )

    _ <- ConsensusPhase
           .run(
             nodeConfig,
             killSignal,
             currentSlotRef,
             networkModule,
             chainModule,
             chainExtender,
             obftBlockImportService,
             consensusBuilder,
             mempool,
             timeToFinalizationTracker,
             nodeStatusProvider,
             consensusMetrics,
             consensusNetworkMetricsUpdater
           )
           .evalTap(_ => readySignal.complete(()) >> IO.delay(logger.info("Node synchronized and running...")))
           .start
  } yield {
    val _getByNumberService   = getByNumberService
    val _currentBranchService = currentBranchService
    new ScEvmNode[IO] {
      override val routes: RpcRoutes[IO]                                   = rpcRoutes
      implicit override val currentBranchService: CurrentBranchService[IO] = _currentBranchService
      override val getByNumberService: GetByNumberService[IO]              = _getByNumberService
      override val transactionMempool: SignedTransactionMempool[IO]        = mempool

      override def consensusService: ConsensusService[IO] = consensusBuilder

      override def obftImportService: BlockImportService[IO] = obftBlockImportService

      override def storage: ObftStorageBuilder[IO] = obftStorageBuilder

      override def slotDerivation: SlotDerivation = builder.slotDerivation

      override def ready: IO[Unit] = readySignal.get
    }

  }

  private def createRoutes(
      branchProvider: BranchProvider[IO],
      currentBranch: CurrentBranch.Signal[IO],
      networkModule: NetworkModule[IO]
  ): IO[RpcRoutes[IO]] =
    for {
      chainMode <- nodeConfig.blockchainConfig match {
                     case _: StandaloneBlockchainConfig => IO.pure(ChainMode.Standalone)
                     case _: SidechainBlockchainConfig  => IO.pure(ChainMode.Sidechain)
                     case _                             => IO.raiseError(new RuntimeException("Unsupported type of blockchain config"))
                   }
      routes <- CommonRoutesDispatcher
                  .createRoutes(
                    keyStoreConfig = nodeConfig.keyStoreConfig,
                    appConfig = appConfig,
                    faucetConfig = nodeConfig.faucetConfig,
                    jsonRpcConfig = nodeConfig.jsonRpcConfig,
                    traceTransactionConfig = nodeConfig.traceTransactionConfig,
                    mempool = mempool,
                    transactionsImportService = networkModule,
                    blockchainConfig = nodeConfig.blockchainConfig,
                    chainMode = chainMode,
                    filterConfig = nodeConfig.filterConfig,
                    blockProvider = blockProvider,
                    branchProvider = branchProvider,
                    storage = io.iohk.scevm.rpc.Storage.apply[IO](obftStorageBuilder),
                    stateStorage = obftStorageBuilder.stateStorage,
                    secureRandom = secureRandom,
                    stableNumberMappingStorage = obftStorageBuilder.stableNumberMappingStorage,
                    transactionSimulator = chainModule,
                    stableTransactionMappingStorage = obftStorageBuilder.transactionMappingStorage
                  )(currentBranch)
                  .map(_.combine(additionalRoutes))
                  .map { routes =>
                    val routesWithBasicServices =
                      routes
                        .withTapirEndpoints(
                          List(
                            HealthCheckService.serverEndpoint[IO](nodeStatusProvider),
                            BuildInfoEndpoint.serverEndpoint[IO]
                          )
                        )

                    if (nodeConfig.jsonRpcHttpServerConfig.openrpcSpecificationEnabled) {
                      routesWithBasicServices
                        .withArmadilloEndpoints(
                          List(
                            OpenRpcDiscoveryService.serverEndpoint[IO](
                              routesWithBasicServices.armadilloEndpoints.map(_.endpoint)
                            )
                          )
                        )
                        .withTapirEndpoints(
                          List(
                            new OpenRpcDocumentationEndpoint[IO](
                              nodeConfig.jsonRpcHttpServerConfig.openrpcRenderingEngineUrl,
                              nodeConfig.jsonRpcHttpServerConfig.openrpcSchemaUrl
                            ).serverEndpoint
                          )
                        )
                    } else routesWithBasicServices
                  }
    } yield routes

}
