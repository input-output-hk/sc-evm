package io.iohk.scevm.rpc.sidechain

import cats.Monad
import cats.syntax.all._
import io.iohk.scevm.rpc.ServiceResponseF
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.sidechain.CrossChainTransactionController.{
  GetOutgoingTransactionsResponse,
  OutgoingTransactionsService
}
import io.iohk.scevm.rpc.sidechain.SidechainController.GetPendingTransactionsResponse
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.sidechain.{PendingTransactionsService, SidechainEpoch}

class CrossChainTransactionController[F[_]: Monad](
    epochCalculator: EpochCalculator[F],
    pendingTransactionsService: PendingTransactionsService[F],
    outgoingTransactionService: OutgoingTransactionsService[F]
) {

  def getOutgoingTransactions(req: SidechainEpochParam): ServiceResponseF[F, GetOutgoingTransactionsResponse] =
    (req match {
      case SidechainEpochParam.Latest =>
        epochCalculator.getCurrentEpoch
          .flatMap(outgoingTransactionService.getOutgoingTransactions)
      case SidechainEpochParam.ByEpoch(epoch) =>
        outgoingTransactionService.getOutgoingTransactions(epoch)
    }).map { result =>
      Either.right[JsonRpcError, GetOutgoingTransactionsResponse](
        GetOutgoingTransactionsResponse(result)
      )
    }

  def getPendingTransactions(): ServiceResponseF[F, GetPendingTransactionsResponse] =
    pendingTransactionsService.getPendingTransactions
      .map(response =>
        GetPendingTransactionsResponse(pending = response.unstable, queued = response.stable).asRight[JsonRpcError]
      )
}

object CrossChainTransactionController {

  final case class GetOutgoingTransactionsResponse(transactions: Seq[OutgoingTransaction])

  trait OutgoingTransactionsService[F[_]] {
    def getOutgoingTransactions(epoch: SidechainEpoch): F[Seq[OutgoingTransaction]]
  }
}
