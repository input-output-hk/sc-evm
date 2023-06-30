package io.iohk.scevm.network

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all._
import io.iohk.scevm.db.storage.BranchProvider
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.BranchFetcher.BranchFetcherResult
import io.iohk.scevm.network.ReceiptsFetcher.ReceiptsFetchError
import org.typelevel.log4cats.LoggerFactory

/** A version of `BranchFetcher` that downloads both the branch and all receipts.
  * To avoid OOM issues with longer chains the branch is first downloaded and stored in the database,
  * then the headers are fetched from the database and the corresponding receipts are downloaded.
  */
final case class BranchWithReceiptsFetcher[F[_]: Monad: LoggerFactory](
    branchFetcher: BranchFetcher[F],
    receiptsFetcher: ReceiptsFetcher[F],
    branchProvider: BranchProvider[F]
) extends BranchFetcher[F] {

  override def fetchBranch(
      from: ObftHeader,
      to: ObftHeader,
      peerIds: NonEmptyList[PeerId]
  ): F[BranchFetcher.BranchFetcherResult] =
    log.info(show"Start fetching branch with receipts, from: $from to $to}") >>
      branchFetcher.fetchBranch(from, to, peerIds).flatMap {
        case BranchFetcher.BranchFetcherResult.Connected(_, _) =>
          branchProvider.fetchChain(to, from).flatMap {
            case Left(branchRetrievalError) =>
              log.error(
                show"Could not fetch branch from storage: ${BranchProvider.userFriendlyMessage(branchRetrievalError)}"
              ) >>
                Monad[F].pure[BranchFetcherResult](
                  BranchFetcherResult.FatalError(BranchProvider.userFriendlyMessage(branchRetrievalError))
                )
            case Right(headers) =>
              val blockHeadersWithTxs = headers.filter(_.containsTransactions)
              if (blockHeadersWithTxs.nonEmpty) {
                log.info(show"Start fetching ${blockHeadersWithTxs.length} receipts from ${peerIds.length} peers") >>
                  log.debug(show"Start fetching ${blockHeadersWithTxs.length} receipts from $peerIds") >>
                  receiptsFetcher.fetchReceipts(blockHeadersWithTxs, peerIds).flatMap[BranchFetcherResult] {
                    case Left(receiptsFetchError) =>
                      log
                        .error(
                          show"Could not fetch receipts: ${ReceiptsFetchError.userFriendlyMessage(receiptsFetchError)}"
                        )
                        .as(
                          BranchFetcherResult
                            .RecoverableError(ReceiptsFetchError.userFriendlyMessage(receiptsFetchError))
                        )
                    case Right(_) =>
                      log
                        .info(show"Successfully fetched ${blockHeadersWithTxs.length} receipts")
                        .as(BranchFetcherResult.Connected(from, to))
                  }
              } else
                log
                  .info("All the block in the branch are empty. No receipts to fetch")
                  .as(BranchFetcherResult.Connected(from, to))
          }
        case e: BranchFetcherResult =>
          log.error(show"Could not fetch branch: ${BranchFetcherResult.userFriendlyMessage(e)}").as(e)
      }

  private val log = LoggerFactory[F].getLoggerFromClass(this.getClass)
}
