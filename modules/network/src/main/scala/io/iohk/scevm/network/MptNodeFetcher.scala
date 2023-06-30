package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{Parallel, Show}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.scevm.db.storage.{EvmCodeStorage, StateStorage}
import io.iohk.scevm.domain.{Account, EvmCode}
import io.iohk.scevm.mpt.Node.Hash
import io.iohk.scevm.mpt._
import io.iohk.scevm.network.MptNodeFetcher.NodeFetchError
import io.iohk.scevm.network.MptNodeFetcher.NodeFetchError.PeersAskFailure
import io.iohk.scevm.network.PeerChannel.MultiplePeersAskFailure
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.NodeData
import org.typelevel.log4cats.LoggerFactory

import scala.util.Try

trait MptNodeFetcher[F[_]] {

  /** Fetches MPT nodes from the network and store them in the database.
    * Used in "fast sync" implementation (eth/63)
    */
  def fetch(nodeHashes: List[Hash], peerIds: NonEmptyList[PeerId]): F[Either[NodeFetchError, Unit]]
}

object MptNodeFetcher {
  sealed trait NodeFetchError
  object NodeFetchError {
    final case class PeersAskFailure(failure: MultiplePeersAskFailure) extends NodeFetchError

    implicit val show: Show[NodeFetchError] = cats.derived.semiauto.show

    def userFriendlyMessage(error: NodeFetchError): String = error match {
      case PeersAskFailure(e) => MultiplePeersAskFailure.userFriendlyMessage(e)
    }
  }
}

class MptNodeFetcherImpl[F[_]: Async: Parallel: LoggerFactory](
    peerChannel: PeerChannel[F],
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    maxMptNodesPerRequest: Int
) extends MptNodeFetcher[F] {
  import MptNodeFetcher._

  /** Fetch MPT nodes by batching them into multiple requests of size `maxMptNodesPerRequest`.
    * The received nodes are then traversed recursively to find more nodes to fetch.
    *
    * It's a breadth-first traversal with an exception when the number of requested nodes is
    * greater than `maxMptNodesPerRequest`. In that case, the remaining nodes are fetched
    * after the first batch (left subtree) is processed.
    */
  override def fetch(mptHashes: List[Hash], peerIds: NonEmptyList[PeerId]): F[Either[NodeFetchError, Unit]] =
    mptHashes
      .grouped(maxMptNodesPerRequest)
      .toList
      .traverse { hashes =>
        fetchFromPeersUnbounded(hashes, peerIds)
      }
      .flatMap { results =>
        results.sequence.map(_.flatten) match {
          case Right(moreHashes) =>
            if (moreHashes.isEmpty)
              log.debug("No more MPT nodes to fetch").as(Right(()))
            else
              log.debug(s"Discovered ${moreHashes.length} more MPT nodes") >>
                fetch(moreHashes, peerIds)
          case Left(error) =>
            log.error(show"Failed to fetch MPT nodes: $error").as(Left(error))
        }
      }

  /** Fetch MPT nodes using peer channel, no batching. Should only be called from `fetchFromPeers`
    * to guarantee that the number of requested nodes is less than/equal to `maxMptNodesPerRequest`.
    * Received nodes and codes are stored in the database .
    */
  private def fetchFromPeersUnbounded(
      mptHashes: List[Hash],
      peers: NonEmptyList[PeerId]
  ): F[Either[NodeFetchError, List[Hash]]] =
    log.info(s"Requesting ${mptHashes.size} MPT nodes") >>
      peerChannel.askPeers[OBFT1.NodeData](peers, OBFT1.GetNodeData(mptHashes)).flatMap {
        case Right(nodeData) =>
          val (nodes, codes) = decodeNodeData(nodeData)
          codes.traverse(saveCode) >>
            traverseNodes(nodes).map(Either.right[NodeFetchError, List[Hash]])
        case Left(error) =>
          log
            .error(show"Failed to fetch MPT nodes: ${MultiplePeersAskFailure.userFriendlyMessage(error)}")
            .as(Left(PeersAskFailure(error)))
      }

  /** Save every node in the database and extract more nodes to fetch.
    */
  private def traverseNodes(nodes: List[MptNode]): F[List[Hash]] =
    nodes
      .traverse {
        case node: LeafNode =>
          saveNode(node) >>
            (decodeAccount(node) match {
              case Some(account) =>
                List(account.storageRoot, account.codeHash).pure
              case None =>
                List.empty[Hash].pure
            })
        case node: BranchNode    => saveNode(node).as(node.children.toList.map(node => ByteString(node.hash)))
        case node: ExtensionNode => saveNode(node).as(List(ByteString(node.next.hash)))
        case node: HashNode      => saveNode(node).as(List(ByteString(node.hash)))
        case NullNode            => List.empty[Hash].pure
      }
      .map(_.flatten)

  private def saveCode(code: EvmCode): F[Unit] = {
    val codeHash = crypto.kec256(code.bytes)
    evmCodeStorage.put(codeHash, code.bytes).pure.void
  }

  /** The received `NodeData` is a list where each item is either an MPT node or a code.
    * Partition them into two lists, as they require different handling.
    */
  private def decodeNodeData(nodeData: NodeData): (List[MptNode], List[EvmCode]) = {
    import io.iohk.scevm.mpt.MptNodeEncoders._

    import scala.util.{Failure, Success, Try}

    nodeData.values.foldLeft((List.empty[MptNode], List.empty[EvmCode])) { case ((mptNodes, codes), bytes) =>
      Try(bytes.toArray[Byte].toMptNode) match {
        case Success(mptNode) => (mptNodes :+ mptNode, codes)
        case Failure(_)       => (mptNodes, codes :+ EvmCode(bytes))
      }
    }
  }

  private def decodeAccount(node: LeafNode): Option[Account] = {
    import io.iohk.scevm.domain.Account.AccountRLPImplicits.AccountDec
    Try(node.value.toArray.toAccount).toOption
  }

  private def saveNode(mptNode: MptNode): F[Unit] =
    stateStorage.saveNode(ByteString(mptNode.hash), MptTraversals.encodeNode(mptNode)).pure

  private val log = LoggerFactory[F].getLoggerFromClass(this.getClass)
}
