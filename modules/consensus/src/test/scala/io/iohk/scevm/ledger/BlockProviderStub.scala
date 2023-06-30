package io.iohk.scevm.ledger

import cats.Applicative
import cats.syntax.all._
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock, ObftBody, ObftHeader}

class BlockProviderStub[F[_]: Applicative](
    blocksByNumber: Map[BlockNumber, ObftBlock],
    blockByHash: Map[BlockHash, ObftBlock],
    bestBlock: ObftBlock,
    stableBlock: ObftBlock
) extends BlockProvider[F] {

  override def getBlock(number: BlockNumber): F[Option[ObftBlock]] = blocksByNumber.get(number).pure[F]

  override def getBestBlock: F[ObftBlock] = bestBlock.pure[F]

  override def getHeader(number: BlockNumber): F[Option[ObftHeader]] = blocksByNumber.get(number).map(_.header).pure[F]

  override def getHeader(hash: BlockHash): F[Option[ObftHeader]] = blockByHash.get(hash).map(_.header).pure[F]

  override def getBody(number: BlockNumber): F[Option[ObftBody]] = blocksByNumber.get(number).map(_.body).pure[F]

  override def getBody(hash: BlockHash): F[Option[ObftBody]] = blockByHash.get(hash).map(_.body).pure[F]

  override def getBestBlockHeader: F[ObftHeader] = bestBlock.header.pure[F]

  override def getBlock(hash: BlockHash): F[Option[ObftBlock]] = blockByHash.get(hash).pure[F]

  override def getStableBlock: F[ObftBlock] = stableBlock.pure[F]

  override def getStableBlockHeader: F[ObftHeader] = getStableBlock.map(_.header)

  override def getHashByNumber(branchTip: ObftHeader)(number: BlockNumber): F[Option[BlockHash]] =
    blocksByNumber.get(number).map(_.hash).pure[F]

}

object BlockProviderStub {
  def apply[F[_]: Applicative](
      blocks: List[ObftBlock],
      bestBlock: ObftBlock,
      stableBlock: ObftBlock
  ): BlockProviderStub[F] =
    new BlockProviderStub[F](
      blocksByNumber = blocks.groupMapReduce(k => k.number)(identity)((a, _) => a),
      blockByHash = blocks.groupMapReduce(k => k.hash)(identity)((a, _) => a),
      bestBlock = bestBlock,
      stableBlock = stableBlock
    )
}
