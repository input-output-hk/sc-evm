package io.iohk.scevm

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Resource}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import io.iohk.bytes.FromBytes
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA}
import io.iohk.scevm.config.StandaloneBlockchainConfig
import io.iohk.scevm.consensus.metrics.{
  PrometheusBlockMetrics,
  PrometheusConsensusMetrics,
  PrometheusPhasesMetrics,
  TimeToFinalizationTrackerImpl
}
import io.iohk.scevm.consensus.pos.{BlockPreExecution, ConsensusBuilderImpl, CurrentBranch, CurrentBranchService}
import io.iohk.scevm.consensus.validators.PostExecutionValidatorImpl
import io.iohk.scevm.consensus.{WorldStateBuilder, WorldStateBuilderImpl}
import io.iohk.scevm.db.storage.GetByNumberServiceImpl
import io.iohk.scevm.domain.Tick
import io.iohk.scevm.exec.mempool.SignedTransactionMempool
import io.iohk.scevm.exec.metrics.PrometheusExecutionMetrics
import io.iohk.scevm.exec.vm.{StorageType, TransactionSimulator, VM, WorldType}
import io.iohk.scevm.extvm.VMSetup
import io.iohk.scevm.ledger._
import io.iohk.scevm.metrics.PrometheusNodeMetrics
import io.iohk.scevm.network.loadAsymmetricCipherKeyPair
import io.iohk.scevm.node.Phase0.Phase0Outcome
import io.iohk.scevm.node.signals.SignalHandlers
import io.iohk.scevm.node.{Builder, MonitoredNodeStatusProvider, Phase0}
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import io.iohk.scevm.rpc.http.JsonRpcHttpServer
import io.iohk.scevm.rpc.router.ArmadilloAdapter
import io.iohk.scevm.rpc.serialization.ArmadilloJsonSupport
import io.iohk.scevm.rpc.sidechain.SidechainRpc
import io.iohk.scevm.sidechain._
import io.iohk.scevm.sidechain.metrics.PrometheusSidechainMetrics
import io.iohk.scevm.storage.metrics.PrometheusStorageMetrics
import io.iohk.scevm.sync.tick.SlotTickImpl
import io.iohk.scevm.tracing.TracingContext
import io.iohk.scevm.utils.{BuildInfo, Logger, NodeStatusProvider, SystemTime}
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.security.SecureRandom

object ScEvm extends Logger {
  def main(args: Array[String]): Unit = {
    Thread.setDefaultUncaughtExceptionHandler((t, e) => log.error("Uncaught exception in thread: " + t, e))
    log.info("Starting EVM sidechain node")
    log.info("EVM sidechain node compiled git revision: {}", BuildInfo.gitRev)
    log.info("EVM sidechain node nix input hash: {}", BuildInfo.nixInputHash)
    val rawConfig  = ConfigFactory.load().getConfig("sc-evm")
    val nodeConfig = ScEvmConfig.fromConfig(rawConfig)

    val configRedacted = redactSensitiveInfo(rawConfig).root()
    log.info(
      "Configuration: {}",
      configRedacted
    )
    log.info("Client version: {}", nodeConfig.appConfig.clientVersion)
    val blockchainConfig = nodeConfig.blockchainConfig

    (for {
      implicit0(t: SystemTime[IO]) <- Resource.eval(SystemTime.liveF[IO])
      slotDerivation: SlotDerivation = SlotDerivation
                                         .live(
                                           blockchainConfig.genesisData.timestamp,
                                           nodeConfig.blockchainConfig.slotDuration
                                         )
                                         .fold(error => throw error, identity)
      ticker =
        new SlotTickImpl[IO](slotDerivation).ticker(nodeConfig.blockchainConfig.slotDuration)
      _                           <- Instrumentation.startMetricsServer[IO](nodeConfig.instrumentationConfig.metricsConfig)
      entryPoint                  <- Instrumentation.tracingEntryPoint[IO](nodeConfig.instrumentationConfig.tracingConfig)
      implicit0(trace: Trace[IO]) <- Resource.eval(TracingContext.create(entryPoint))
      builder                     <- createApp(nodeConfig, ticker, slotDerivation)
      killSignal                  <- Resource.eval(Deferred[IO, Either[Throwable, Unit]])
      node                        <- builder.startApp(killSignal)

      appRoutes = ArmadilloAdapter(
                    node.routes,
                    ArmadilloJsonSupport.jsonSupport,
                    nodeConfig.jsonRpcHttpServerConfig.requestTimeout
                  )
      jsonRpcHttpServer <- JsonRpcHttpServer[IO](() => appRoutes, nodeConfig.jsonRpcHttpServerConfig)
      _                 <- jsonRpcHttpServer.run()
    } yield killSignal)
      .use(_.get.map {
        case Left(error) => log.error(s"Stopping EVM sidechain node because of an error: ${error.getMessage}", error)
        case Right(_)    => log.info("Stopping EVM sidechain node...")
      })
      .unsafeRunSync()
  }

  private def redactSensitiveInfo(config: Config): Config = {
    val redacted = ConfigValueFactory.fromAnyRef("***")
    config.withValue("pos.private-keys", redacted).withValue("sidechain.datasource.db-sync.password", redacted)
  }

  // scalastyle:off method.length
  def createApp(
      nodeConfig: ScEvmConfig,
      ticker: fs2.Stream[IO, Tick],
      slotDerivation: SlotDerivation,
      blockPreExecutionOverride: Option[BlockPreExecution[IO]] = None
  )(implicit trace: Trace[IO], st: SystemTime[IO]): Resource[IO, Builder] = {
    val blockchainConfig                    = nodeConfig.blockchainConfig
    implicit val logging: LoggerFactory[IO] = Slf4jFactory[IO]
    for {
      vm                              <- VMSetup.vm[WorldType, StorageType](nodeConfig.vmConfig, blockchainConfig)
      nodeMetrics                     <- PrometheusNodeMetrics[IO]
      _                               <- Resource.eval(nodeMetrics.recordGitRevision(BuildInfo.gitRev))
      storageMetrics                  <- PrometheusStorageMetrics[IO]
      blockMetrics                    <- PrometheusBlockMetrics[IO]
      phasesMetrics                   <- PrometheusPhasesMetrics[IO]
      secureRandom                     = new SecureRandom()
      nodeKey                          = loadAsymmetricCipherKeyPair(nodeConfig.appConfig.nodeKeyFile, secureRandom)
      nodeStatusProvider              <- Resource.eval(NodeStatusProvider[IO](nodeKey))
      monitoredNodeStatusProvider      = new MonitoredNodeStatusProvider[IO](nodeStatusProvider, phasesMetrics)
      consensusMetrics                <- PrometheusConsensusMetrics[IO]
      timeToFinalizationTracker       <- Resource.eval(TimeToFinalizationTrackerImpl[IO](nodeConfig.posConfig.metrics))
      Phase0Outcome(genesis, storage) <- Phase0.run[IO](blockchainConfig, nodeConfig.dbConfig, storageMetrics)
      _                               <- SignalHandlers.setupBackupSignal(storage.backupStorage)
      currentBranchService: CurrentBranchService[IO] <- Resource.eval(
                                                          CurrentBranchService.init[IO](
                                                            storage.appStorage,
                                                            storage.blockchainStorage,
                                                            storage.stableNumberMappingStorage,
                                                            storage.transactionMappingStorage,
                                                            genesis.header,
                                                            blockMetrics,
                                                            storageMetrics,
                                                            timeToFinalizationTracker,
                                                            consensusMetrics
                                                          )
                                                        )
      implicit0(currentBranch: CurrentBranch.Signal[IO]) = currentBranchService.signal
      executionMetrics                                  <- PrometheusExecutionMetrics[IO]
      mempool                                           <- Resource.eval(SignedTransactionMempool[IO](nodeConfig.mempoolConfig, executionMetrics))
      getByNumberService                                 = new GetByNumberServiceImpl(storage.blockchainStorage, storage.stableNumberMappingStorage)
      worldStateBuilder = new WorldStateBuilderImpl(
                            storage.evmCodeStorage,
                            getByNumberService,
                            storage.stateStorage,
                            nodeConfig.blockchainConfig
                          )
      blockProvider = new BlockProviderImpl[IO](storage.blockchainStorage, getByNumberService)
      (chainModule, additionalRoutes) <-
        createChainModule(nodeConfig, vm, mempool, blockProvider, worldStateBuilder, blockPreExecutionOverride, ticker)
      ledger = Ledger.build(nodeConfig.blockchainConfig)(
                 chainModule,
                 storage.blockchainStorage,
                 chainModule,
                 storage,
                 vm,
                 blockMetrics,
                 worldStateBuilder
               )
      consensusBuilder <- Resource.eval(
                            ConsensusBuilderImpl.build(nodeConfig.blockchainConfig)(
                              ledger,
                              currentBranchService,
                              storage,
                              consensusMetrics
                            )
                          )
    } yield new Builder(
      nodeConfig,
      nodeKey,
      slotDerivation,
      chainModule,
      secureRandom,
      storage,
      genesis.header,
      consensusBuilder,
      monitoredNodeStatusProvider,
      blockMetrics,
      consensusMetrics,
      timeToFinalizationTracker,
      mempool,
      blockProvider,
      getByNumberService,
      additionalRoutes,
      ledger,
      currentBranchService
    )
  }
  // scalastyle:on method.length

  // scalastyle:off parameter.number method.length
  private def createChainModule(
      nodeConfig: ScEvmConfig,
      vm: VM[IO, WorldType, StorageType],
      mempool: SignedTransactionMempool[IO],
      blockProvider: BlockProvider[IO],
      worldStateBuilder: WorldStateBuilder[IO],
      blockPreExecutionOverride: Option[BlockPreExecution[IO]],
      ticker: fs2.Stream[IO, Tick]
  )(implicit
      trace: Trace[IO],
      st: SystemTime[IO],
      currentBranch: CurrentBranch.Signal[IO],
      loggerFactory: LoggerFactory[IO]
  ): Resource[IO, (ChainModule[IO], RpcRoutes[IO])] =
    for {
      metrics <- PrometheusSidechainMetrics[IO]()
      module <- nodeConfig.blockchainConfig match {
                  case blockchainConfig: SidechainBlockchainConfig =>
                    def sidechainModuleForSignatureScheme[
                        SignatureScheme <: AbstractSignatureScheme
                    ](implicit signatureFromBytes: FromBytes[SignatureScheme#Signature]) = SidechainModuleBuilder
                      .createSidechainModule[IO, SignatureScheme](
                        blockchainConfig,
                        nodeConfig.extraSidechainConfig,
                        nodeConfig.posConfig,
                        mempool,
                        vm,
                        metrics,
                        worldStateBuilder,
                        ticker
                      )
                      .map { sidechainModule =>
                        (
                          sidechainModule,
                          SidechainRpc.createSidechainRpcRoutes(
                            nodeConfig.jsonRpcConfig,
                            blockProvider,
                            sidechainModule,
                            blockchainConfig,
                            worldStateBuilder
                          )
                        )
                      }

                    blockchainConfig.signingScheme match {
                      case ECDSA => sidechainModuleForSignatureScheme[ECDSA]
                      case scheme =>
                        throw new IllegalStateException(s"Unrecognized cross-chain signing scheme: $scheme")
                    }
                  case standaloneBlockchain: StandaloneBlockchainConfig =>
                    val preExecution = blockPreExecutionOverride
                      .getOrElse(BlockPreExecution.noop[IO])
                    val transactionSimulator =
                      TransactionSimulator[IO](
                        nodeConfig.blockchainConfig,
                        vm,
                        preExecution.prepare
                      )

                    val chainModule =
                      SidechainModule.standalone(
                        new RoundRobinLeaderElection[IO](standaloneBlockchain.validators),
                        mempool,
                        new PostExecutionValidatorImpl[IO],
                        preExecution,
                        transactionSimulator,
                        ticker
                      )

                    Resource.pure[IO, (ChainModule[IO], RpcRoutes[IO])]((chainModule, RpcRoutes.empty[IO]))

                  case _ =>
                    Resource.raiseError[IO, (ChainModule[IO], RpcRoutes[IO]), Throwable](
                      new RuntimeException("Unsupported type of blockchain config")
                    )
                }
    } yield module
  // scalastyle:on parameter.number

}
