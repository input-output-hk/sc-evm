package io.iohk.scevm.network

import akka.actor.ActorRef
import cats.effect.{Async, Deferred, IO, Resource}
import fs2.Pipe
import fs2.concurrent.{SignallingRef, Topic}
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.metrics.TimeToFinalizationTracker
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.domain.{NodeId, ObftBlock, Slot}
import io.iohk.scevm.exec.mempool.SignedTransactionMempool
import io.iohk.scevm.metrics.Fs2Monitoring
import io.iohk.scevm.network.gossip.BlockGossip
import io.iohk.scevm.network.handshaker.{ObftHandshaker, ObftHandshakerConfig}
import io.iohk.scevm.network.metrics.{BlockPropagationMetrics, NetworkMetrics}
import io.iohk.scevm.network.p2p.ProtocolVersions
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlock
import io.iohk.scevm.network.rlpx.AuthHandshaker
import io.iohk.scevm.network.stats.PeerStatisticsActor
import io.iohk.scevm.network.utils.TypedActorSystem
import org.typelevel.log4cats.LoggerFactory

import java.time.Clock

class NetworkModuleBuilder(
    networkConfig: NetworkConfig,
    syncConfig: SyncConfig,
    blockchainConfig: BlockchainConfig,
    handshakerConfiguration: ObftHandshakerConfig[IO],
    branchFetcherConfig: BranchFetcherConfig,
    selfNodeId: NodeId,
    authHandshaker: AuthHandshaker,
    killSignal: Deferred[IO, Either[Throwable, Unit]],
    storage: NetworkModuleStorage[IO],
    timeToFinalizationTracker: TimeToFinalizationTracker[IO]
)(implicit loggerFactory: LoggerFactory[IO]) { builder =>
  private val selfPeerId: PeerId = PeerId(selfNodeId.toHex)

  // scalastyle:off method.length
  // scalastyle:off parameter.number
  // scalastyle:off line.size.limit
  def build(
      networkMetrics: NetworkMetrics[IO],
      currentSlotRef: SignallingRef[IO, Slot],
      mempool: SignedTransactionMempool[IO],
      blockPropagationMetrics: BlockPropagationMetrics[IO]
  )(implicit
      currentBranchSignal: CurrentBranch.Signal[IO],
      headerValidator: HeaderValidator[IO]
  ): Resource[IO, NetworkModule[IO]] =
    for {
      classicSystem          <- TypedActorSystem[IO]("sc_evm_system").map(_.classicSystem)
      peerEventsTopic        <- FilteredTopic(PeerEvent.filter[IO])
      implicit0(clock: Clock) = Clock.systemUTC()
      peersStateRef          <- Resource.eval(SignallingRef[IO, Map[PeerId, PeerWithInfo]](Map.empty))

      _ <- peerEventsTopic
             .subscribe(syncConfig.messageSourceBuffer)
             .through(PeerWithInfo.aggregateState(networkMetrics))
             .evalTap(state => peersStateRef.update(_ => state))
             .evalTap(peers => PeerWithInfo.logPeersDetails[IO](peers))
             .compile
             .drain
             .onError(e => killSignal.complete(Left(e)).void)
             .background
      handshaker = ObftHandshaker(handshakerConfiguration)

      peerEventBus = PeerEventBus(peerEventsTopic, syncConfig.messageSourceBuffer)
      peerStatistics = classicSystem.actorOf(
                         PeerStatisticsActor.props(
                           peerEventBus,
                           // `slotCount * slotDuration` should be set so that it's at least as long
                           // as any client of the `PeerStatisticsActor` requires.
                           slotDuration = networkConfig.peer.statSlotDuration,
                           slotCount = networkConfig.peer.statSlotCount
                         ),
                         "peer-statistics"
                       )
      peerManager = classicSystem.actorOf(
                      PeerManagerActor.props(
                        networkConfig.bootstrapNodes,
                        networkConfig.peer,
                        peerEventBus,
                        peerStatistics,
                        handshaker,
                        authHandshaker,
                        ProtocolVersions.PV1,
                        networkMetrics,
                        blockPropagationMetrics
                      ),
                      "peer-manager"
                    )
      _ <- Resource.eval(IO {
             val server =
               classicSystem.actorOf(ServerActor.props(handshakerConfiguration.nodeStatusHolder, peerManager), "server")

             peerManager ! PeerManagerActor.StartConnecting

             server ! ServerActor.StartServer(networkConfig.server.listenAddress)
           })

      peerActionsTopic <- Resource.eval(PeerAction.createTopic[IO])
      _ <- peerActionsTopic
             .subscribe(syncConfig.messageSourceBuffer)
             .through(PeerAction.sendMessages(peerManager, peersStateRef))
             .compile
             .drain
             .onError(e => killSignal.complete(Left(e)).void)
             .background
      blocksTopic <- Resource.eval(Topic[IO, ObftBlock])
      _ <-
        publishNewBlocks(
          peerEventsTopic.subscribe(syncConfig.messageSourceBuffer),
          blocksTopic
        ).compile.drain
          .onError(e => killSignal.complete(Left(e)).void)
          .background

      transactionsImportService = new TransactionsImportServiceImpl(mempool, currentSlotRef)
      _ <- peerEventProcessing(
             mempool,
             peerEventsTopic,
             peersStateRef,
             peerManager,
             transactionsImportService
           ).compile.drain
             .onError(e => killSignal.complete(Left(e)).void)
             .background
      branchFetcher = new BackwardBranchFetcher[IO](
                        new BackwardBlocksFetcher[IO](
                          TopicBasedPeerChannel(
                            peerEventsTopic,
                            peerActionsTopic,
                            branchFetcherConfig.requestTimeout,
                            branchFetcherConfig.subscriptionQueueSize
                          ),
                          storage,
                          storage,
                          headerValidator,
                          timeToFinalizationTracker
                        ),
                        branchFetcherConfig,
                        networkMetrics.syncDownloadedBlocksCounter
                      )
      mptNodeFetcher = new MptNodeFetcherImpl[IO](
                         TopicBasedPeerChannel(
                           peerEventsTopic,
                           peerActionsTopic,
                           branchFetcherConfig.requestTimeout,
                           branchFetcherConfig.subscriptionQueueSize
                         ),
                         storage.getStateStorage,
                         storage.getCodeStorage,
                         networkConfig.peer.hostConfiguration.maxMptComponentsPerMessage
                       )
      receiptsFetcher = new ReceiptsFetcherImpl[IO](
                          TopicBasedPeerChannel(
                            peerEventsTopic,
                            peerActionsTopic,
                            branchFetcherConfig.requestTimeout,
                            branchFetcherConfig.subscriptionQueueSize
                          ),
                          storage.getReceiptStorage,
                          networkConfig.peer.hostConfiguration.maxBlocksPerReceiptsMessage
                        )
    } yield new NetworkModuleImpl(
      peersStateRef,
      syncConfig,
      peerActionsTopic,
      transactionsImportService,
      blocksTopic,
      branchFetcher,
      mptNodeFetcher,
      receiptsFetcher
    )

  private def publishNewBlocks(
      peerEvents: fs2.Stream[IO, PeerEvent],
      blocksTopic: Topic[IO, ObftBlock]
  ): fs2.Stream[IO, ObftBlock] =
    peerEvents
      .collect { case PeerEvent.MessageFromPeer(_, NewBlock(block)) => block }
      .evalTap(block => blocksTopic.publish1(block))

  // scalastyle:off parameter.number
  private def peerEventProcessing(
      mempool: SignedTransactionMempool[IO],
      peerEventsTopic: Topic[IO, PeerEvent],
      peersStateRef: SignallingRef[IO, Map[PeerId, PeerWithInfo]],
      peerManager: ActorRef,
      transactionsImportService: TransactionsImportServiceImpl[IO]
  )(implicit
      currentBranch: CurrentBranch.Signal[IO],
      headerValidator: HeaderValidator[IO]
  ): fs2.Stream[IO, Unit] = {
    val processingPipes = Seq(
      blockchainHostPipe(storage, mempool),
      BlockGossip(
        headerValidator,
        blockchainConfig.stabilityParameter,
        syncConfig.gossipCacheFactor,
        peersStateRef
      ).pipe,
      TransactionsHandler.pipe(mempool, transactionsImportService, peersStateRef.continuous),
      StableHeaderRequester[IO].pipe(syncConfig)
    )
    PeerEventProcessingStream
      .getStream(
        syncConfig,
        peerEventsTopic,
        peerManager,
        processingPipes,
        peersStateRef,
        selfPeerId
      )
  }
  // scalastyle:on parameter.number

  /** Handles RequestMessages */
  private def blockchainHostPipe[F[_]: CurrentBranch.Signal: Async](
      storage: NetworkModuleStorage[F],
      mempool: SignedTransactionMempool[F]
  ): Pipe[F, PeerEvent, PeerAction] =
    _.collect { case PeerEvent.MessageFromPeer(peerId, message: OBFT1.RequestMessage) =>
      (peerId, message)
    }
      .through(Fs2Monitoring.probe("blockchain_host"))
      .through(
        BlockchainHost.pipe(
          networkConfig.peer.hostConfiguration,
          mempool,
          storage
        )
      )
      .map { case (peerId, message) =>
        PeerAction.MessageToPeer(peerId, message)
      }
}
