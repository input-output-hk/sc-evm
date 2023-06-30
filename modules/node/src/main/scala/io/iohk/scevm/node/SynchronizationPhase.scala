package io.iohk.scevm.node

import akka.http.scaladsl.server.Route
import cats.effect.unsafe.IORuntime
import cats.effect.{Async, IO, Resource, unsafe}
import cats.syntax.all._
import cats.~>
import io.iohk.scevm.ScEvmConfig
import io.iohk.scevm.consensus.pos.CurrentBranchService
import io.iohk.scevm.db.storage.BranchProvider
import io.iohk.scevm.ledger.{BlockProvider, Ledger}
import io.iohk.scevm.network.SyncConfig.SyncMode
import io.iohk.scevm.network.{BranchWithReceiptsFetcher, NetworkModule, SyncConfig}
import io.iohk.scevm.rpc.http.JsonRpcHttpServer
import io.iohk.scevm.rpc.tapir.HealthCheckService
import io.iohk.scevm.sync.StableBranchSync.{StableBranchSyncError, StableBranchSyncResult}
import io.iohk.scevm.sync._
import io.iohk.scevm.utils.{NodeState, NodeStateProvider, NodeStateUpdater}
import io.janstenpickle.trace4cats.inject.Trace
import org.typelevel.log4cats.LoggerFactory
import sttp.tapir.integ.cats.syntax.ServerEndpointImapK
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.akkahttp.AkkaHttpServerInterpreter

import scala.concurrent.{ExecutionContext, Future}

object SynchronizationPhase {

  def runBlocking[F[_]: Async: Trace: ToFuture: LoggerFactory](
      nodeConfig: ScEvmConfig,
      networkModule: NetworkModule[F],
      ledger: Ledger[F],
      blockProvider: BlockProvider[F],
      currentBranchService: CurrentBranchService[F],
      nodeStateProvider: NodeStateProvider[F] with NodeStateUpdater[F],
      branchProvider: BranchProvider[F]
  ): F[Unit] =
    Async[F].whenA(nodeConfig.syncConfig.startSync)((for {
      minimalRoutes <-
        Resource.pure[F, Route](toAkkaRoutes(List(HealthCheckService.serverEndpoint[F](nodeStateProvider))))
      minimalJsonRpcHttpServer <- JsonRpcHttpServer(() => minimalRoutes, nodeConfig.jsonRpcHttpServerConfig)
      _                        <- minimalJsonRpcHttpServer.run()
    } yield ()).use { _ =>
      runSync(
        nodeConfig,
        networkModule,
        ledger,
        blockProvider,
        currentBranchService,
        nodeStateProvider,
        branchProvider
      )
    })

  private def toAkkaRoutes[F[_]: Async: ToFuture](tapirEndpoints: List[ServerEndpoint[Any, F]]): Route = {
    val ioToFuture = Lambda[F ~> Future](r => ToFuture[F].unsafeToFuture(r)(IORuntime.global))
    val futureToIo = Lambda[Future ~> F](f => Async[F].fromFuture(Async[F].delay(f)))
    AkkaHttpServerInterpreter()(ExecutionContext.global).toRoute(tapirEndpoints.map(_.imapK(ioToFuture)(futureToIo)))
  }

  // scalastyle:off method.length
  private def runSync[F[_]: Async: Trace: LoggerFactory](
      nodeConfig: ScEvmConfig,
      networkModule: NetworkModule[F],
      ledger: Ledger[F],
      blockProvider: BlockProvider[F],
      currentBranchService: CurrentBranchService[F],
      nodeStateUpdater: NodeStateUpdater[F],
      branchProvider: BranchProvider[F]
  ): F[Unit] = {
    val log = LoggerFactory[F].getLoggerFromClass(this.getClass)
    for {
      currentStable <- currentBranchService.signal.get.map(_.stable)

      fastSync = stableBranchSyncFast(
                   networkModule,
                   branchProvider,
                   ledger,
                   blockProvider,
                   currentBranchService,
                   nodeConfig.syncConfig
                 )

      fullSync = stableBranchSyncFull(
                   networkModule,
                   ledger,
                   blockProvider,
                   currentBranchService,
                   nodeConfig.syncConfig
                 ).pure

      stableBranchSync <- (currentStable.number.toLong, nodeConfig.syncConfig.syncMode) match {
                            case (0, SyncMode.Fast) =>
                              log.info("Sync mode: fast-sync (starting from genesis)") >> fastSync
                            case (0, SyncMode.Full) =>
                              log.info("Sync mode: full-sync (starting from genesis)") >> fullSync
                            case (_, SyncMode.Fast) =>
                              log.info(
                                "Sync mode: full-sync (overriding fast-sync as the current stable is not at genesis)"
                              ) >> fullSync
                            case (_, SyncMode.Full) =>
                              log.info("Sync mode: full-sync (starting from the current stable)") >> fullSync
                          }

      monitoredStableBranchSync: StableBranchSync[F] =
        new MonitoredStableBranchSync[F](
          stableBranchSync,
          new io.iohk.scevm.sync.MonitoredStableBranchSync.NodeStateUpdater[F] {
            override def setSynchronizationPhase(): F[Unit] =
              nodeStateUpdater.setNodeState(NodeState.Syncing)

            override def unsetPhase(): F[Unit] =
              nodeStateUpdater.setNodeState(NodeState.TransitioningFromSyncing)
          }
        )

      _ <- monitoredStableBranchSync
             .catchup()
             .flatMap {
               case StableBranchSyncResult.Success(from, to) =>
                 log.info(show"Synchronization to a stable branch finished: successfully synced from $from up to $to")
               case StableBranchSyncResult.NetworkInitialization =>
                 log.info("Unable to find nodes to sync to, starting consensus phase from genesis")
               case error: StableBranchSyncError =>
                 import StableBranchSyncResult.show
                 val msg = s"Unable to sync: ${show.show(error)}"
                 Async[F].raiseError[Unit](new RuntimeException(msg))
             }
    } yield ()
  }

  private def stableBranchSyncFast[F[_]: Async: Trace: LoggerFactory](
      networkModule: NetworkModule[F],
      branchProvider: BranchProvider[F],
      ledger: Ledger[F],
      blockProvider: BlockProvider[F],
      currentBranchService: CurrentBranchService[F],
      syncConfig: SyncConfig
  ): F[StableBranchSync[F]] = {

    val branchWithReceiptsFetcher = new BranchWithReceiptsFetcher[F](
      networkModule.branchFetcher,
      networkModule.receiptsFetcher,
      branchProvider
    )

    val fullSyncMode = new FullSyncMode[F](networkModule.branchFetcher, ledger)

    for {
      fastSyncMode <- FastSyncMode[F](
                        branchWithReceiptsFetcher,
                        networkModule.mptNodeFetcher,
                        fullSyncMode
                      )
    } yield new StableBranchSyncImpl[F](
      syncConfig,
      fastSyncMode,
      blockProvider,
      currentBranchService,
      networkModule.peers
    )
  }

  private def stableBranchSyncFull[F[_]: Async: Trace: LoggerFactory](
      networkModule: NetworkModule[F],
      ledger: Ledger[F],
      blockProvider: BlockProvider[F],
      currentBranchService: CurrentBranchService[F],
      syncConfig: SyncConfig
  ): StableBranchSync[F] =
    new StableBranchSyncImpl[F](
      syncConfig,
      new FullSyncMode[F](networkModule.branchFetcher, ledger),
      blockProvider,
      currentBranchService,
      networkModule.peers
    )

}

trait ToFuture[F[_]] {
  def unsafeToFuture[A](fa: F[A])(implicit runtime: unsafe.IORuntime): Future[A]
}
object ToFuture {
  def apply[F[_]](implicit F: ToFuture[F]): ToFuture[F] = implicitly
}
object IoToFuture extends ToFuture[IO] {
  override def unsafeToFuture[A](fa: IO[A])(implicit runtime: unsafe.IORuntime): Future[A] = fa.unsafeToFuture()
}
