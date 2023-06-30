package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import cats.implicits._
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.BranchProvider
import io.iohk.scevm.domain.{BlockHash, BlockNumber, BlockOffset, ObftHeader}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.rpc.ServiceResponse

class InspectController(
    currentBranchSignal: CurrentBranch.Signal[IO],
    blockProvider: BlockProvider[IO],
    branchProvider: BranchProvider[IO]
) {
  import InspectController._

  /** Given following chain structure, where:
    * - E2 is the latest stable block
    * - G22 is the current best block
    *
    *           /- D1
    *          /        /- F21 - G21
    * A - B - C - D2 - E2
    *    /             \- F22 - G22
    *   /___offset________________/
    *
    * Offset represents distance from the current best block to where we would like to see the chain structure.
    * If not provided we will set it to stability parameter meaning that we will look up all the alternative chains since the latest stable block
    * This function will look up blocks that are direct descendants of a given starting block and expand their branches recursively.
    * It allows us to go back in time and see what were the alternative chains.
    *
    * Warning: caller should be careful not to request too many blocks since it might OOM
    */

  def getChain(maybeOffset: Option[BlockOffset]): ServiceResponse[GetChainResponse] =
    for {
      current     <- currentBranchSignal.get
      fromHeader  <- getFromHeader(maybeOffset, current)
      descendants <- getDescendantBlocks(fromHeader)
      data         = descendants.map(h => (h.parentHash, (Block(h.hash, h.number, h.slotNumber, h.publicSigningKey.bytes))))
      blocks       = data.map(_._2)
      links        = data.map { case (parent, node) => Link(parent, node.hash) }
      best        <- getStableSubchain(current, maybeOffset)
    } yield Right(GetChainResponse(blocks, links, best, current.stable.hash))

  private def getFromHeader(offsetOpt: Option[BlockOffset], current: CurrentBranch): IO[ObftHeader] =
    offsetOpt.fold(IO.pure(current.stable)) { off =>
      val bestNumber          = current.best.number.value
      val selectedBlockNumber = if (bestNumber > off.value) BlockNumber(bestNumber - off.value) else BlockNumber(0)
      blockProvider.getBlock(selectedBlockNumber).map { opt =>
        opt.fold(current.stable)(_.header) // assume stable default
      }
    }

  /** includes the starting block
    */
  private def getDescendantBlocks(from: ObftHeader): IO[Seq[ObftHeader]] =
    (List(from), List(from)).tailRecM[IO, List[ObftHeader]] { case (toFetch, acc) =>
      val children = toFetch.parTraverse(h => branchProvider.getChildren(h.hash).map(_.toList)).map(_.flatten)
      children.map { headers =>
        if (headers.isEmpty) Right(acc)
        else Left((headers, headers ++ acc))
      }
    }

  private def getStableSubchain(currentBranch: CurrentBranch, offsetOpt: Option[BlockOffset]): IO[List[BlockHash]] = {
    val offset: BigInt = offsetOpt.fold(currentBranch.stable.number.value)(_.value)
    val best           = currentBranch.best
    val range          = ((best.number.value - offset) to best.number.value).map(i => BlockNumber(i)).toList
    for {
      blocks <- range.parTraverse(blockProvider.getBlock)
      hashes  = blocks.flatten.map(_.hash)
    } yield hashes
  }

}

object InspectController {
  import io.iohk.bytes.ByteString
  import io.iohk.scevm.domain.{BlockHash, Slot}

  final case class Block(
      hash: BlockHash,
      blockNumber: BlockNumber,
      slot: Slot,
      signer: ByteString
  )

  final case class Link(
      parent: BlockHash,
      child: BlockHash
  )

  final case class GetChainResponse(
      nodes: Seq[Block],
      links: Seq[Link],
      best: Seq[BlockHash],
      stable: BlockHash
  )

}
