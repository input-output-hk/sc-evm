package io.iohk.scevm.rpc.controllers

import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.domain.{BlockNumber, ObftBlock, ObftHeader}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.rpc.controllers.BlockResolver.{ResolvedBlock, ResolvedHeader}

trait BlockResolver[F[_]] {
  def resolveBlock(blockParam: BlockParam): F[Option[ResolvedBlock]]
  def resolveHeader(blockParam: BlockParam): F[Option[ResolvedHeader]]
}

class BlockResolverImpl[F[_]: Sync](
    blockProvider: BlockProvider[F]
) extends BlockResolver[F] {

  override def resolveBlock(blockParam: BlockParam): F[Option[ResolvedBlock]] =
    (for {
      ResolvedHeader(header, _) <- OptionT(resolveHeader(blockParam))
      body                      <- OptionT(blockProvider.getBody(header.hash))
    } yield ResolvedBlock(ObftBlock(header, body))).value

  override def resolveHeader(blockParam: BlockParam): F[Option[ResolvedHeader]] =
    (blockParam match {
      case BlockParam.ByNumber(blockNumber) => blockProvider.getHeader(blockNumber)
      case BlockParam.Earliest              => blockProvider.getHeader(BlockNumber(0))
      case BlockParam.Latest                => blockProvider.getBestBlockHeader.map(_.some)
      case BlockParam.Stable                => blockProvider.getStableBlockHeader.map(_.some)
      case BlockParam.Pending               =>
        // There is not notion of "pending" block with PoS
        blockProvider.getBestBlockHeader.map(_.some)
    }).map(_.map(ResolvedHeader(_)))
}

object BlockResolver {
  final case class ResolvedBlock(block: ObftBlock, pendingState: Boolean = false)
  final case class ResolvedHeader(header: ObftHeader, pendingState: Boolean = false)
}
