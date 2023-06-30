package io.iohk.scevm.network

import cats.Monad
import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._
import fs2.Pipe
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{BlocksDistance, ObftBlock, SignedTransaction}
import io.iohk.scevm.network.p2p.messages.OBFT1._
import io.iohk.scevm.storage.execution.SlotBasedMempool

object BlockchainHost {

  /** Sets up a handler for the response to RequestMessage */
  def pipe[F[_]: Async: CurrentBranch.Signal](
      hostConfiguration: HostConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      storage: NetworkModuleStorage[F]
  ): Pipe[F, (PeerId, RequestMessage), (PeerId, ResponseMessage)] = {
    val handler = messageHandler(hostConfiguration, mempool, storage)
    _.mapAsyncUnordered[F, (PeerId, ResponseMessage)](hostConfiguration.parallelism) { case (peerId, requestMessage) =>
      handler(requestMessage).map(peerId -> _)
    }
  }

  //scalastyle:off method.length
  private def messageHandler[F[_]: Async: Monad: CurrentBranch.Signal](
      hostConfiguration: HostConfig,
      mempool: SlotBasedMempool[F, SignedTransaction],
      storage: NetworkModuleStorage[F]
  ): RequestMessage => F[ResponseMessage] = {
    case GetNodeData(requestId, mptElementsHashes) =>
      mptElementsHashes
        .take(hostConfiguration.maxMptComponentsPerMessage)
        .traverse(storage.getNode)
        .map(blobs => NodeData(requestId, blobs.flatten))

    case GetReceipts(requestId, blockHashes) =>
      blockHashes
        .take(hostConfiguration.maxBlocksPerReceiptsMessage)
        .traverse(storage.getReceiptsByHash)
        .map(receipts => Receipts(requestId, receipts.flatten))

    case GetBlockBodies(requestId, hashes) =>
      hashes
        .take(hostConfiguration.maxBlocksBodiesPerMessage)
        .traverse(storage.getBlockBody)
        .map(bodies => BlockBodies(requestId, bodies.flatten))

    case GetBlockHeaders(requestId, block, maxHeaders, skip, reverse) =>
      (for {
        tipHeader <- OptionT.liftF(CurrentBranch.best[F])
        blockNumber <- block.fold(
                         blockNumber => OptionT.pure[F](blockNumber),
                         blockHash => OptionT(storage.getBlockHeader(blockHash)).map(_.number)
                       )
        blockHeaders <- OptionT.liftF(
                          storage.blockHeaders(tipHeader)(
                            blockNumber,
                            skip,
                            Math.min(maxHeaders, hostConfiguration.maxBlocksHeadersPerMessage),
                            reverse
                          )
                        )
      } yield blockHeaders).getOrElse(Seq.empty).map(BlockHeaders(requestId, _))

    case GetPooledTransactions(requestId, hashes) =>
      val hashSet = hashes.toSet
      mempool.getAll.map(txs => PooledTransactions(requestId, txs.filter(tx => hashSet(tx.hash))))

    case GetStableHeaders(requestId, ancestorsOffsets) =>
      for {
        stableHeader <- CurrentBranch.stable
        ancestorHeaders <-
          ancestorsOffsets
            .take(hostConfiguration.maxStableHeadersPerMessage)
            .traverse { ancestorOffset =>
              val ancestorNumber = stableHeader.number - BlocksDistance(ancestorOffset)
              storage.getHeaderByNumber(stableHeader, ancestorNumber)
            }
            .map(_.flatten)
      } yield StableHeaders(requestId, stableHeader, ancestorHeaders)
    case GetFullBlocks(requestId, blockHash, count) =>
      (for {
        tipHeader <- OptionT(storage.getBlockHeader(blockHash))
        blockHeaders <- OptionT.liftF {
                          storage.blockHeaders(tipHeader)(
                            blockNumber = tipHeader.number,
                            skip = 0,
                            math.min(count, hostConfiguration.maxBlocksPerFullBlocksMessage),
                            reverse = true
                          )
                        }
        fullBlocks <- OptionT.liftF {
                        blockHeaders.flatTraverse { header =>
                          storage.getBlockBody(header.hash).map {
                            case Some(block) => List(ObftBlock(header, block))
                            case None        => List.empty
                          }
                        }
                      }
      } yield fullBlocks).getOrElse(Seq.empty).map(FullBlocks(requestId, _))
  }
  //scalastyle:on

}
