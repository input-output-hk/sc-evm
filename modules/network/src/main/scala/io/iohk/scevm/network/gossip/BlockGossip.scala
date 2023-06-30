package io.iohk.scevm.network.gossip

import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.Pipe
import fs2.concurrent.Signal
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.consensus.validators.HeaderValidator.HeaderError
import io.iohk.scevm.domain.{BlockHash, ObftBlock}
import io.iohk.scevm.metrics.Fs2Monitoring
import io.iohk.scevm.network.gossip.BlockGossip.{BlockGossipResult, BlockGossipState}
import io.iohk.scevm.network.p2p.Message
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlock
import io.iohk.scevm.network.{PeerAction, PeerEvent, PeerId, PeerWithInfo}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.collection.immutable.Queue

class BlockGossip[F[_]: Async](
    headerValidator: HeaderValidator[F],
    cacheSize: Int,
    peersStateRef: Signal[F, Map[PeerId, PeerWithInfo]]
) {
  implicit private val log: Logger[F] = Slf4jLogger.getLogger[F]

  /** Handles gossiping of new blocks */
  def pipe: Pipe[F, PeerEvent, PeerAction] =
    _.through(filterNewBlock)
      .through(Fs2Monitoring.probe("block_gossip"))
      .through(analyzeNewBlock)
      .through(processResult)

  private def filterNewBlock: Pipe[F, PeerEvent, (PeerId, NewBlock)] =
    _.collect { case PeerEvent.MessageFromPeer(peerId, message: NewBlock) => (peerId, message) }

  private def analyzeNewBlock: Pipe[F, (PeerId, NewBlock), BlockGossipResult] =
    _.evalMapAccumulate(BlockGossipState(cacheSize, Queue.empty)) {
      case (state1, (peerId1, NewBlock(tentativeNewBlock))) =>
        analyzeNewBlockAtState(state1, peerId1)(tentativeNewBlock)
      case (state, (peerId, message)) =>
        (state, BlockGossipResult.UnrelatedMessage(message, peerId): BlockGossipResult).pure[F]
    }
      .map { case (_, blockGossipResult) => blockGossipResult }

  private def analyzeNewBlockAtState(state: BlockGossipState, peerId: PeerId)(
      tentativeNewBlock: ObftBlock
  ): F[(BlockGossipState, BlockGossipResult)] =
    headerValidator
      .validate(tentativeNewBlock.header)
      .map {
        case Left(error) =>
          (state, BlockGossipResult.InvalidBlock(tentativeNewBlock, error))
        case Right(_) =>
          if (state.gossipedHashes.contains(tentativeNewBlock.hash)) {
            (state, BlockGossipResult.AlreadyGossiped(tentativeNewBlock): BlockGossipResult)
          } else {
            (
              state.addGossipedEntry(tentativeNewBlock.hash),
              BlockGossipResult.BroadcastBlock(tentativeNewBlock, peerId)
            )
          }
      }

  private def processResult: Pipe[F, BlockGossipResult, PeerAction] =
    _.flatMap {
      case BlockGossipResult.InvalidBlock(block, reason) =>
        val ret =
          Logger[F].warn(show"Received invalid block $block (reason:${HeaderError.userFriendlyMessage(reason)})") >>
            Seq.empty.pure
        fs2.Stream.evalSeq(ret)
      case BlockGossipResult.BroadcastBlock(block, source) =>
        val ret = for {
          peers               <- peersStateRef.get.map(_.values.toList)
          peerIdsWithoutSource = peers.map(_.peer.id).filter(_ != source)
        } yield peerIdsWithoutSource.map(peerId => PeerAction.MessageToPeer(peerId, NewBlock(block)))
        fs2.Stream.evalSeq(ret)
      case BlockGossipResult.AlreadyGossiped(block) =>
        val ret = Logger[F].debug(show"Block $block has already been gossiped") >>
          Seq.empty.pure
        fs2.Stream.evalSeq(ret)
      case BlockGossipResult.UnrelatedMessage(message, source) =>
        val ret =
          Logger[F].debug(show"Ignoring message ${message.toShortString} from $source: not related to Block Gossip") >>
            Seq.empty.pure
        fs2.Stream.evalSeq(ret)
    }
}

object BlockGossip {
  sealed private trait BlockGossipResult
  private object BlockGossipResult {
    final case class BroadcastBlock(obftBlock: ObftBlock, source: PeerId)    extends BlockGossipResult
    final case class InvalidBlock(obftBlock: ObftBlock, reason: HeaderError) extends BlockGossipResult
    final case class AlreadyGossiped(obftBlock: ObftBlock)                   extends BlockGossipResult
    final case class UnrelatedMessage(message: Message, source: PeerId)      extends BlockGossipResult
  }

  /** Basic Gossiped block cache, with a fixed length to prevent out of memory error
    * @param maxCacheSize usually the stability-k parameter
    * @param gossipedHashes hashes of already gossiped blocks
    */
  final private case class BlockGossipState(maxCacheSize: Int, gossipedHashes: Queue[BlockHash]) {
    def addGossipedEntry(hash: BlockHash): BlockGossipState =
      this.copy(gossipedHashes =
        if (gossipedHashes.size > maxCacheSize) gossipedHashes.tail.enqueue(hash) else gossipedHashes.enqueue(hash)
      )
  }

  def apply[F[_]: Async](
      headerValidator: HeaderValidator[F],
      stabilityParameter: Int,
      gossipCacheFactor: Double,
      peersStateRef: Signal[F, Map[PeerId, PeerWithInfo]]
  ): BlockGossip[F] = {
    val cacheSize = (stabilityParameter * gossipCacheFactor).toInt
    new BlockGossip(headerValidator, cacheSize, peersStateRef)
  }
}
