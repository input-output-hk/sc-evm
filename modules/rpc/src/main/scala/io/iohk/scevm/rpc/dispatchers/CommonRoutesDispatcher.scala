package io.iohk.scevm.rpc.dispatchers

import cats.effect.IO
import cats.effect.std.Random
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.ethereum.Crypto
import io.iohk.scevm.config.{AppConfig, BlockchainConfig, KeyStoreConfig}
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage._
import io.iohk.scevm.exec.mempool.SignedTransactionMempool
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.exec.vm.{EvmCall, TransactionSimulator}
import io.iohk.scevm.keystore.KeyStore
import io.iohk.scevm.ledger.{BlockProvider, NonceProviderImpl}
import io.iohk.scevm.network.NewTransactionsListener
import io.iohk.scevm.rpc._
import io.iohk.scevm.rpc.armadillo.debug.DebugEndpoints
import io.iohk.scevm.rpc.armadillo.eth._
import io.iohk.scevm.rpc.armadillo.faucet.FaucetEndpoints
import io.iohk.scevm.rpc.armadillo.inspect.InspectEndpoints
import io.iohk.scevm.rpc.armadillo.net.NetEndpoints
import io.iohk.scevm.rpc.armadillo.personal.PersonalEndpoints
import io.iohk.scevm.rpc.armadillo.sanity.SanityEndpoints
import io.iohk.scevm.rpc.armadillo.scevm.{EvmSidechainBlockEndpoints, EvmSidechainNetworkEndpoints}
import io.iohk.scevm.rpc.armadillo.txpool.TxPoolEndpoints
import io.iohk.scevm.rpc.armadillo.web3.Web3Endpoints
import io.iohk.scevm.rpc.controllers.NetController.ChainMode
import io.iohk.scevm.rpc.controllers._
import io.iohk.scevm.rpc.faucet.{FaucetConfig, FaucetController}
import io.iohk.scevm.utils.Logger
import sttp.tapir.server.ServerEndpoint

import java.security.SecureRandom

class CommonRoutesDispatcher(
    ethBlocksController: EthBlocksController,
    ethInfoController: EthInfoController,
    personalController: PersonalController,
    ethWorldStateController: EthWorldStateController,
    netController: NetController,
    web3Controller: Web3Controller,
    ethTransactionController: EthTransactionController,
    ethVMController: EthVMController,
    faucetController: FaucetController,
    sanityController: SanityController[IO],
    txPoolController: TxPoolController[IO],
    inspectController: InspectController,
    debugController: DebugController[IO],
    config: JsonRpcConfig
) {
  // Endpoints handled by Armadillo
  val armadilloEndpoints: List[JsonRpcServerEndpoint[IO]] = {
    val debug = if (config.debug) {
      DebugEndpoints.endpoints(debugController)
    } else {
      List.empty
    }

    val eth = if (config.eth) {
      EthAccountsEndpoints.endpoints(personalController) ++
        EthBlocksEndpoints.getEthEndpoints(ethBlocksController) ++
        EthInfoEndpoints.endpoints(ethInfoController) ++
        EthTransactionEndpoints.endpoints(ethTransactionController, personalController) ++
        EthVMEndpoints.getEthVMEndpoints(ethVMController) ++
        EthWorldEndpoints.getEthWorldEndpoints(ethWorldStateController)
    } else {
      List.empty
    }

    val faucet = if (config.faucet) {
      FaucetEndpoints.endpoints(faucetController)
    } else {
      List.empty
    }

    val inspect = if (config.inspect) {
      InspectEndpoints.endpoints(inspectController)
    } else {
      List.empty
    }

    val scevm = if (config.scevm) {
      EvmSidechainBlockEndpoints.getEvmSidechainEndpoints(ethBlocksController) ++
        EvmSidechainNetworkEndpoints.getEndpoints(netController)
    } else {
      List.empty
    }

    val net = if (config.net) {
      NetEndpoints.endpoints(netController)
    } else {
      List.empty
    }

    val personal = if (config.personal) {
      PersonalEndpoints.getPersonalEndpoints(personalController)
    } else {
      List.empty
    }

    val sanity = if (config.sanity) {
      SanityEndpoints.endpoints(sanityController)
    } else {
      List.empty
    }

    val txPoolRoutes = if (config.txPool) {
      TxPoolEndpoints.getTxPoolEndpoints(txPoolController)
    } else {
      List.empty
    }

    val web3 = if (config.web3) {
      Web3Endpoints.endpoints(web3Controller)
    } else {
      List.empty
    }

    debug ++ eth ++ faucet ++ inspect ++ scevm ++ net ++ personal ++ sanity ++ txPoolRoutes ++ web3
  }
}

object CommonRoutesDispatcher extends Logger {
  final case class RpcRoutes[F[_]](
      armadilloEndpoints: List[JsonRpcServerEndpoint[F]],
      tapirEndpoints: List[ServerEndpoint[Any, F]]
  ) {
    def combine(other: RpcRoutes[F]): RpcRoutes[F] = RpcRoutes(
      armadilloEndpoints ++ other.armadilloEndpoints,
      tapirEndpoints ++ other.tapirEndpoints
    )

    def withArmadilloEndpoints(endpoints: List[JsonRpcServerEndpoint[F]]): RpcRoutes[F] =
      this.copy(armadilloEndpoints = armadilloEndpoints ++ endpoints)

    def withTapirEndpoints(endpoints: List[ServerEndpoint[Any, F]]): RpcRoutes[F] =
      this.copy(tapirEndpoints = tapirEndpoints ++ endpoints)
  }

  object RpcRoutes {
    def empty[F[_]]: RpcRoutes[F] = RpcRoutes(List.empty, List.empty)
  }

  // scalastyle:off method.length
  // scalastyle:off parameter.number
  def createRoutes(
      keyStoreConfig: KeyStoreConfig,
      appConfig: AppConfig,
      faucetConfig: FaucetConfig,
      jsonRpcConfig: JsonRpcConfig,
      traceTransactionConfig: TraceTransactionConfig,
      mempool: SignedTransactionMempool[IO],
      transactionsImportService: NewTransactionsListener[IO],
      blockchainConfig: BlockchainConfig,
      chainMode: ChainMode,
      filterConfig: FilterConfig,
      blockProvider: BlockProvider[IO],
      branchProvider: BranchProvider[IO],
      storage: Storage[IO],
      stateStorage: StateStorage,
      secureRandom: SecureRandom,
      stableNumberMappingStorage: StableNumberMappingStorage[IO],
      transactionSimulator: TransactionSimulator[EvmCall[IO, *]],
      stableTransactionMappingStorage: StableTransactionMappingStorage[IO]
  )(implicit currentBranch: CurrentBranch.Signal[IO]): IO[RpcRoutes[IO]] =
    for {
      implicit0(_secureRandom: Random[IO]) <- Random.javaUtilRandom[IO](secureRandom)
      implicit0(crypto: Crypto[IO])         = new Crypto[IO](secureRandom)
      keyStore                             <- KeyStore.create[IO](keyStoreConfig)
      dispatcher <- IO {
                      val resolveBlock: BlockResolver[IO] = new BlockResolverImpl(blockProvider)
                      lazy val web3Controller             = new Web3Controller(appConfig)
                      lazy val ethInfoController          = new EthInfoController(blockchainConfig)
                      val worldBuilder = new WorldStateBuilderImpl[IO](
                        storage,
                        new GetByNumberServiceImpl(
                          storage,
                          stableNumberMappingStorage
                        ),
                        stateStorage,
                        blockchainConfig
                      )
                      val accountProvider = new AccountServiceImpl(worldBuilder, blockchainConfig)
                      val transactionBlockService =
                        new GetTransactionBlockServiceImpl[IO](storage, stableTransactionMappingStorage)
                      lazy val ethTransactionController =
                        new EthTransactionController(
                          blockchainConfig.ethCompatibility,
                          storage,
                          storage,
                          blockchainConfig,
                          storage,
                          transactionsImportService,
                          SignedTransactionValidatorImpl,
                          accountProvider,
                          resolveBlock,
                          blockProvider,
                          filterConfig
                        )
                      lazy val ethVMController = new EthVMController(
                        blockchainConfig,
                        resolveBlock,
                        transactionSimulator,
                        worldBuilder
                      )
                      val nonceProvider = new NonceServiceImpl[IO](
                        new NonceProviderImpl(
                          getAllFromMemPool = mempool.getAll,
                          blockchainConfig = blockchainConfig
                        ),
                        CurrentBranch.best[IO],
                        worldBuilder
                      )
                      lazy val personalController = new PersonalController(
                        keyStore,
                        transactionsImportService,
                        blockchainConfig,
                        nonceProvider,
                        SignedTransactionValidatorImpl,
                        accountProvider
                      )
                      lazy val netController = new NetController(blockchainConfig, chainMode)
                      lazy val ethBlocksController =
                        new EthBlocksController(resolveBlock, blockProvider, blockchainConfig.chainId)
                      lazy val ethWorldStateController = new EthWorldStateController(
                        resolveBlock,
                        stateStorage,
                        blockchainConfig,
                        accountProvider,
                        worldBuilder
                      )
                      val faucetController = new FaucetController(
                        faucetConfig = faucetConfig,
                        nonceService = nonceProvider,
                        blockchainConfig = blockchainConfig,
                        transactionsImportService = transactionsImportService
                      )
                      val sanityController: SanityController[IO] =
                        new SanityControllerImpl[IO](
                          storage,
                          storage
                        )

                      val txPoolController: TxPoolController[IO] = new TxPoolControllerImpl[IO](
                        mempool,
                        worldBuilder,
                        currentBranch,
                        blockchainConfig
                      )

                      val inspectController: InspectController =
                        new InspectController(currentBranch, blockProvider, branchProvider)

                      val debugController: DebugController[IO] =
                        new DebugControllerImpl(
                          transactionBlockService,
                          storage,
                          blockchainConfig,
                          worldBuilder,
                          traceTransactionConfig.debugTraceTxDefaultTimeout
                        )

                      new CommonRoutesDispatcher(
                        ethBlocksController,
                        ethInfoController,
                        personalController,
                        ethWorldStateController,
                        netController,
                        web3Controller,
                        ethTransactionController,
                        ethVMController,
                        faucetController,
                        sanityController,
                        txPoolController,
                        inspectController,
                        debugController,
                        jsonRpcConfig
                      )
                    }
    } yield RpcRoutes(dispatcher.armadilloEndpoints, List.empty)
  // scalastyle:on method.length
  // scalastyle:on parameter.number

}
