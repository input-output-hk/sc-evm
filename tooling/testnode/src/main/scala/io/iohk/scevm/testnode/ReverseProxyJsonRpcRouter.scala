package io.iohk.scevm.testnode

import akka.http.scaladsl.server.Route
import cats._
import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all._
import fs2.concurrent.Topic
import io.iohk.armadillo.server.{EndpointOps, EndpointOverride}
import io.iohk.scevm.config.{BlockchainConfig, KeyStoreConfig}
import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranch, CurrentBranchService}
import io.iohk.scevm.db.storage.{GetByNumberService, ObftStorageBuilder}
import io.iohk.scevm.domain.{Address, MemorySlot, SignedTransaction, Tick, Token}
import io.iohk.scevm.ledger.{BlockImportService, SlotDerivation}
import io.iohk.scevm.node.ScEvmNode
import io.iohk.scevm.rpc.FilterConfig
import io.iohk.scevm.rpc.armadillo.eth.EthTransactionApi
import io.iohk.scevm.rpc.armadillo.web3.Web3Api
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion
import io.iohk.scevm.rpc.dispatchers.CommonRoutesDispatcher.RpcRoutes
import io.iohk.scevm.rpc.router.ArmadilloAdapter
import io.iohk.scevm.rpc.serialization.ArmadilloJsonSupport
import io.iohk.scevm.storage.execution.SlotBasedMempool
import io.iohk.scevm.testnode.ReverseProxyJsonRpcRouter.AllocatedResource
import io.iohk.scevm.testnode.TestJRpcController._
import io.iohk.scevm.testnode.rpc.armadillo.hardhat.HardhatEndpoints
import io.iohk.scevm.testnode.rpc.armadillo.test.{TestDebugEndpoints, TestEndpoints}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import io.iohk.scevm.utils.{Logger, SystemTime}
import io.iohk.scevm.{ScEvm, ScEvmConfig}
import io.janstenpickle.trace4cats.inject.Trace

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

class ReverseProxyJsonRpcRouter(
    testNodeConfig: TestNodeConfig,
    nodeConfig: ScEvmConfig,
    ticksTopic: Topic[IO, Tick]
) extends Logger {

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  // This var is not thread safe. This should not be necessary as retesteth
  // will never call setChainParams twice at the same time.
  private var currentNode: AllocatedResource[ScEvmNode[IO]] =
    AllocatedResource(ReverseProxyJsonRpcRouter.EmptyNode, IO.unit)
  private val testController = TestJRpcController[IO](
    ScEvmNodeProxyImpl,
    nodeConfig.blockchainConfig,
    nodeConfig.keyStoreConfig,
    nodeConfig.filterConfig
  )

  { // init the proxy
    implicit val global: IORuntime = IORuntime.global
    ScEvmNodeProxyImpl
      .setNewNode(
        nodeConfig.keyStoreConfig,
        nodeConfig.blockchainConfig,
        nodeConfig.filterConfig
      )
      .unsafeRunSync()
  }

  private def overridingEndpoints: List[EndpointOverride[IO]] =
    if (testNodeConfig.hardhatMode) {
      List(
        EthTransactionApi.eth_sendRawTransaction.`override`.runAfterLogic(
          testController.mineBlocks(BlockCount(1)).void
        ),
        EthTransactionApi.eth_sendTransaction.`override`.runAfterLogic(
          testController.mineBlocks(BlockCount(1)).void
        ),
        Web3Api.web3_clientVersion.`override`.thenReturn(
          IO.pure(ClientVersion("hardhatnetwork-sc-evm-interface").asRight[Unit])
        )
      )
    } else {
      List.empty
    }

  /** This routes are reevaluated for each request.
    * We need this behavior because ets calls set_chain_params that recreates whole SC EVM and so the rpc controllers.
    * This works because akka supports dynamic routing (https://doc.akka.io/docs/akka-http/current/routing-dsl/index.html#dynamic-routing-example)
    */
  def appRoutes: Route = {
    val additionalArmadilloEndpoints = {
      val debugEndpoints = TestDebugEndpoints.endpoints(testController)

      val hardhatEndpoints = if (testNodeConfig.hardhatMode) {
        HardhatEndpoints.endpoints(new HardhatApiController(ScEvmNodeProxyImpl))
      } else {
        List.empty
      }

      val testEndpoints = TestEndpoints.endpoints(testController)

      debugEndpoints ++ hardhatEndpoints ++ testEndpoints
    }

    ArmadilloAdapter(
      currentNode.resource.routes.copy(
        armadilloEndpoints = currentNode.resource.routes.armadilloEndpoints ++ additionalArmadilloEndpoints
      ),
      ArmadilloJsonSupport.jsonSupport,
      nodeConfig.jsonRpcHttpServerConfig.requestTimeout,
      overridingEndpoints
    )
  }

  object ScEvmNodeProxyImpl extends ScEvmNodeProxy[IO] {
    implicit def currentBranchSignal: CurrentBranch.Signal[IO] = currentNode.resource.currentBranchService.signal

    private val preExec = new TestNodeBlockPreExec[IO]

    def setNewNode(
        keyStoreConfig: KeyStoreConfig,
        blockchainConfig: BlockchainConfig,
        filterConfig: FilterConfig
    ): IO[Unit] =
      (for {
        _                                     <- Resource.eval(IO(preExec.clear()))
        _                                     <- Resource.eval(currentNode.release)
        implicit0(systemTime: SystemTime[IO]) <- Resource.eval(SystemTime.liveF[IO])
        implicit0(trace: Trace[IO])            = Trace.Implicits.noop[IO]
        newConfig = nodeConfig
                      .copy(
                        keyStoreConfig = keyStoreConfig,
                        blockchainConfig = blockchainConfig,
                        filterConfig = filterConfig
                      )
        slotDerivation = SlotDerivation
                           .live(
                             newConfig.blockchainConfig.genesisData.timestamp,
                             newConfig.blockchainConfig.slotDuration
                           )
                           .fold(error => throw error, identity)
        // scalastyle:off magic.number
        builder <- ScEvm.createApp(
                     nodeConfig = newConfig,
                     ticker = ticksTopic.subscribe(100),
                     slotDerivation = slotDerivation,
                     blockPreExecutionOverride = Some(preExec)
                   )
        // scalastyle:on magic.number
        app <- builder.startApp(Deferred.unsafe)
        _   <- Resource.eval(app.ready)
      } yield app).allocated
        .map { case (res, release) =>
          currentNode = AllocatedResource(res, release)
        }

    def triggerTick(timestamp: UnixTimestamp): IO[Unit] = for {
      slot          <- IO.fromEither(currentNode.resource.slotDerivation.getSlot(timestamp))
      currentBranch <- CurrentBranch.get[IO]
      _             <- ticksTopic.publish1(Tick(slot, timestamp))
      // wait until the best blocked changed
      //  30 seconds is the max time akka will wait and so we should wait 30 seconds
      //  because a few long test (static_Call50000 for instance) actually need that time to run.
      //  However, it's way too long to wait 30 seconds right now because a lot of test
      //  will trigger the timeout right now
      _ <- Monad[IO]
             .iterateUntil(
               CurrentBranch
                 .bestHash[IO]
                 .delayBy(20.millis)
                 .timeout(3.seconds)
             )(_ != currentBranch.best.hash)
    } yield ()

    override def currentBranchService: CurrentBranchService[IO] = currentNode.resource.currentBranchService

    override def getByNumberService: GetByNumberService[IO] = currentNode.resource.getByNumberService

    override def consensusService: ConsensusService[IO] = currentNode.resource.consensusService

    override def obftImportService: BlockImportService[IO] = currentNode.resource.obftImportService

    override def transactionPool: SlotBasedMempool[IO, SignedTransaction] = currentNode.resource.transactionMempool

    override def storage: ObftStorageBuilder[IO] = currentNode.resource.storage

    override def addStorageModification(address: Address, memorySlot: MemorySlot, value: Token): IO[Unit] =
      IO(preExec.addStorageModification(address, memorySlot, value))
  }
}

object ReverseProxyJsonRpcRouter {

  final case class AllocatedResource[T](resource: T, release: IO[Unit])

  object EmptyNode extends ScEvmNode[IO] {
    override def routes: RpcRoutes[IO] = RpcRoutes.empty[IO]

    override def currentBranchService: CurrentBranchService[IO] = ???

    override def getByNumberService: GetByNumberService[IO] = ???

    override def consensusService: ConsensusService[IO] = ???

    override def obftImportService: BlockImportService[IO] = ???

    override def transactionMempool: SlotBasedMempool[IO, SignedTransaction] = ???

    override def storage: ObftStorageBuilder[IO] = ???

    override def slotDerivation: SlotDerivation = ???

    override def ready: IO[Unit] = IO.unit
  }
}
