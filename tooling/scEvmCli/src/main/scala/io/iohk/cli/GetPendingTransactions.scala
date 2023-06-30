package io.iohk.cli

import cats.effect.{ExitCode, IO}
import cats.implicits._
import com.monovore.decline.Opts
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import io.iohk.jsonRpcClient.JsonRpcClient
import io.iohk.scevm.domain._
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainTxHash}
import sttp.model.Uri

object GetPendingTransactions {
  final private case class Params(scEvmUrl: Uri, recipient: Option[Address], verbose: Boolean)

  private def opts(
      configDefaultUrl: Option[Uri]
  ) =
    Opts.subcommand("pending-txs", "displays pending and queued transactions from main chain to sidechain")(
      (CommonOpts.nodeUrl(configDefaultUrl), CommonOpts.recipientFilter, CommonOpts.verbose).mapN(Params)
    )

  def apply(configDefaultUrl: => Option[Uri]): Opts[IO[ExitCode]] = opts(configDefaultUrl).map {
    case Params(scEvmUrl, maybeRecipient, verbose) =>
      JsonRpcClient.asResource[IO](scEvmUrl, verbose).use { rpcClient =>
        for {
          transactions <-
            rpcClient.callEndpoint[GetPendingTransactionsResponse]("sidechain_getPendingTransactions", Nil)
          filteredTransactions = maybeRecipient.fold(transactions)(filterRecipient(_, transactions))
          // scalastyle:off regex
          _ <- IO.println(filteredTransactions.asJson.printWith(Printer.spaces2))
          // scalastyle:on
        } yield ExitCode.Success
      }
  }

  private def filterRecipient(recipient: Address, transactions: GetPendingTransactionsResponse) =
    transactions.copy(
      pending = transactions.pending.filter(_.recipient == recipient),
      queued = transactions.queued.filter(_.recipient == recipient)
    )

  final case class GetPendingTransactionsResponse(
      pending: List[ValidIncomingCrossChainTransaction],
      queued: List[ValidIncomingCrossChainTransaction]
  )

  final case class ValidIncomingCrossChainTransaction(
      recipient: Address,
      value: Token,
      txId: MainchainTxHash,
      stableAtMainchainBlock: MainchainBlockNumber
  )
}
