package io.iohk.scevm.sidechain

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain.BlockContext
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.sidechain.PendingTransactionsService.PendingTransactionsResponse
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction

trait PendingTransactionsService[F[_]] {
  def getPendingTransactions: F[PendingTransactionsResponse]
}

object PendingTransactionsService {
  final case class PendingTransactionsResponse(
      stable: List[ValidIncomingCrossChainTransaction],
      unstable: List[ValidIncomingCrossChainTransaction]
  )
}

class PendingTransactionsServiceImpl[F[_]: Sync](
    txProvider: PendingIncomingCrossChainTransactionProvider[F],
    blockProvider: BlockProvider[F],
    worldStateBuilder: WorldStateBuilder[F]
) extends PendingTransactionsService[F] {
  override def getPendingTransactions: F[PendingTransactionsResponse] = for {
    header <- blockProvider.getBestBlockHeader
    result <- worldStateBuilder
                .getWorldStateForBlock(header.stateRoot, BlockContext.from(header))
                .use(world => txProvider.getPendingTransactions(world))
  } yield result
}
