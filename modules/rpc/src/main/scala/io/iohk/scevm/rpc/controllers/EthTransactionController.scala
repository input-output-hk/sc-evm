package io.iohk.scevm.rpc.controllers

import cats.data.{EitherT, NonEmptyList, OptionT}
import cats.effect.IO
import cats.syntax.all._
import fs2.Stream
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.{BlockchainConfig, EthCompatibilityConfig}
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.{BlocksReader, GetTransactionBlockService}
import io.iohk.scevm.domain.SignedTransaction.SignedTransactionRLPImplicits.SignedTransactionDec
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.validators.{SignedTransactionError, SignedTransactionValid, SignedTransactionValidator}
import io.iohk.scevm.ledger.{BlockProvider, BloomFilter}
import io.iohk.scevm.network.NewTransactionsListener
import io.iohk.scevm.network.TransactionsImport.TransactionsFromRpc
import io.iohk.scevm.rpc.controllers.BlockResolver.ResolvedHeader
import io.iohk.scevm.rpc.controllers.EthTransactionController.ReceiptReader
import io.iohk.scevm.rpc.domain.JsonRpcError.JsonRpcErrorThrowable
import io.iohk.scevm.rpc.domain.{JsonRpcError, RpcFullTransactionResponse}
import io.iohk.scevm.rpc.{AccountService, FilterConfig, ServiceResponse}
import io.iohk.scevm.utils.Logger

import scala.util.{Failure, Success, Try}

object EthTransactionController {
  sealed trait GetLogsRequest {
    def address: Seq[Address]
    def topics: Seq[TopicSearch]
  }

  final case class GetLogsRequestByBlockHash(blockHash: BlockHash, address: Seq[Address], topics: Seq[TopicSearch])
      extends GetLogsRequest
  final case class GetLogsRequestByRange(
      fromBlock: EthBlockParam,
      toBlock: EthBlockParam,
      address: Seq[Address],
      topics: Seq[TopicSearch]
  ) extends GetLogsRequest

  sealed trait TopicSearch
  sealed trait NestableTopicSearch                                 extends TopicSearch
  final case object AnythingTopicSearch                            extends TopicSearch
  final case class OrTopicSearch(values: Set[NestableTopicSearch]) extends TopicSearch
  final case class ExactTopicSearch(value: ByteString)             extends NestableTopicSearch

  trait ReceiptReader[F[_]] {
    def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]]
  }
}

class EthTransactionController(
    ethCompatibilityConfig: EthCompatibilityConfig,
    blocksReader: BlocksReader[IO],
    receiptStorage: ReceiptReader[IO],
    blockchainConfig: BlockchainConfig,
    transactionBlockService: GetTransactionBlockService[IO],
    transactionsImportService: NewTransactionsListener[IO],
    signedTxValidator: SignedTransactionValidator,
    accountProvider: AccountService[IO],
    resolveBlock: BlockResolver[IO],
    blockProvider: BlockProvider[IO],
    filterConfig: FilterConfig
)(implicit current: CurrentBranch.Signal[IO])
    extends Logger {
  import EthTransactionController._

  def gasPrice(): ServiceResponse[Gas] = IO.pure(Right(Gas(ethCompatibilityConfig.blockBaseFee)))

  def sendRawTransaction(data: ByteString): ServiceResponse[TransactionHash] =
    Try(data.toArray.toSignedTransaction) match {
      case Success(signedTransaction) =>
        SignedTransaction.getSender(signedTransaction)(blockchainConfig.chainId) match {
          case Some(txSender) =>
            for {
              maybeValid <- validateTransaction(signedTransaction, txSender)
              hashOrError <- maybeValid match {
                               case Right(_) =>
                                 transactionsImportService
                                   .importTransaction(TransactionsFromRpc(NonEmptyList.one(signedTransaction)))
                                   .as(Right(signedTransaction.hash))
                               case Left(error) =>
                                 IO.pure(Left(JsonRpcError.InvalidParams(error.toString)))
                             }
            } yield hashOrError
          case None => IO.pure(Left(JsonRpcError.InvalidParams("Error deriving Transaction sender")))
        }
      case Failure(_) =>
        IO.pure(Left(JsonRpcError.InvalidRequest))
    }

  private def validateTransaction(
      signedTx: SignedTransaction,
      address: Address
  ): IO[Either[SignedTransactionError, SignedTransactionValid]] = for {
    bestHeader <- CurrentBranch.best[IO]
    account    <- accountProvider.getAccount(bestHeader, address)
    valid <- IO.pure(
               signedTxValidator.validateForMempool(
                 signedTx,
                 account,
                 bestHeader.number,
                 bestHeader.gasLimit,
                 accumGasUsed = BigInt(0)
               )(blockchainConfig)
             )
  } yield valid

  def getTransactionReceipt(transactionHash: TransactionHash): ServiceResponse[Option[TransactionReceiptResponse]] =
    (for {
      CurrentBranch(stable, best) <- OptionT.liftF(CurrentBranch.get[IO])
      TransactionLocation(blockHash, txIndex) <-
        OptionT(transactionBlockService.getTransactionLocation(stable, best.hash)(transactionHash))
      ObftBlock(header, body) <- OptionT(blocksReader.getBlock(blockHash))
      receipts                <- OptionT(receiptStorage.getReceiptsByHash(blockHash))
      signedTx                <- OptionT(IO.pure(body.transactionList.lift(txIndex)))
      receipt                 <- OptionT(IO.pure(receipts.lift(txIndex)))
      sender                  <- OptionT(IO.pure(SignedTransaction.getSender(signedTx)(blockchainConfig.chainId)))
    } yield {

      val gasUsed =
        if (txIndex == 0) receipt.cumulativeGasUsed
        else receipt.cumulativeGasUsed - receipts(txIndex - 1).cumulativeGasUsed

      TransactionReceiptResponse(
        receipt = receipt,
        signedTx = signedTx,
        signedTransactionSender = sender,
        transactionIndex = txIndex,
        obftHeader = header,
        gasUsedByTransaction = gasUsed
      )
    }).value.map(maybeReceipt => Right(maybeReceipt))

  def getTransactionByHash(transactionHash: TransactionHash): ServiceResponse[Option[RpcFullTransactionResponse]] =
    (for {
      CurrentBranch(stable, best) <- OptionT.liftF(CurrentBranch.get[IO])
      TransactionLocation(blockHash, txIndex) <-
        OptionT(transactionBlockService.getTransactionLocation(stable, best.hash)(transactionHash))
      ObftBlock(header, body) <- OptionT(blocksReader.getBlock(blockHash))
      signedTx                <- OptionT.fromOption[IO](body.transactionList.lift(txIndex))
    } yield RpcFullTransactionResponse.from(
      signedTx,
      txIndex,
      header.hash,
      header.number,
      blockchainConfig.chainId
    )).value.map(maybeTransactionOrError => maybeTransactionOrError.sequence)

  def getLogs(req: GetLogsRequest): ServiceResponse[Seq[TransactionLogWithRemoved]] =
    req match {
      case GetLogsRequestByBlockHash(blockHash, _, _) =>
        (for {
          header <- EitherT.fromOptionM(blocksReader.getBlockHeader(blockHash), IO.pure(JsonRpcError.BlockNotFound))
          logs   <- EitherT(getLogsFromBlock(req, header))
        } yield logs).value

      case GetLogsRequestByRange(fromBlock, toBlock, _, _) =>
        val from: IO[Option[ResolvedHeader]] = resolveBlock.resolveHeader(fromBlock)
        val to: IO[Option[ResolvedHeader]]   = resolveBlock.resolveHeader(toBlock)
        IO.both(from, to).flatMap {
          case (Some(ResolvedHeader(fromHeader, _)), Some(ResolvedHeader(toHeader, _)))
              if fromHeader.number <= toHeader.number =>
            getLogsBetweenBlocks(req, fromHeader, toHeader)
          case _ => IO(Left(JsonRpcError.BlockNotFound))
        }
    }

  private def getLogsBetweenBlocks(
      req: GetLogsRequest,
      fromHeader: ObftHeader,
      toHeader: ObftHeader
  ): IO[Either[JsonRpcError, List[TransactionLogWithRemoved]]] =
    Stream
      .iterateEval[IO, ObftHeader](toHeader) { header =>
        blockProvider.getHeader(header.parentHash).flatMap {
          case Some(header) => IO.pure(header)
          case None         => IO.raiseError(JsonRpcErrorThrowable(JsonRpcError.InvalidParams()))
        }
      }
      .takeThrough { header =>
        // Genesis block doesn't have parent hash, so don't try to iterate after it
        header.number > BlockNumber(0) && header.hash != fromHeader.hash
      }
      .take(filterConfig.filterMaxBlockToFetch)
      .evalMap(header => getLogsFromBlock(req, header))
      .compile
      .toList
      .map(_.flatSequence)

  private def topicMatchesBloomFilter(bloomFilter: ByteString)(
      topicSearch: TopicSearch
  ): Boolean =
    topicSearch match {
      case ExactTopicSearch(value)                      => BloomFilter.contains(bloomFilter, value)
      case EthTransactionController.AnythingTopicSearch => true
      case OrTopicSearch(values)                        => values.exists(topicMatchesBloomFilter(bloomFilter))
    }

  private def getLogsFromBlock(
      req: GetLogsRequest,
      header: ObftHeader
  ): IO[Either[JsonRpcError, List[TransactionLogWithRemoved]]] = {
    lazy val filteredAddresses =
      req.address.filter(address => BloomFilter.contains(header.logsBloom, address.bytes))
    lazy val topicsMatch = req.topics.forall(topicMatchesBloomFilter(header.logsBloom))

    if (filteredAddresses.isEmpty && req.address.nonEmpty)
      IO.pure(Right(Nil))
    else if (!topicsMatch)
      IO.pure(Right(Nil))
    else
      getLogsForAddressesAndTopics(header, filteredAddresses, req.topics).value
  }

  private def getLogsForAddressesAndTopics(header: ObftHeader, addresses: Seq[Address], topics: Seq[TopicSearch]) =
    for {
      receipts <- EitherT.fromOptionF(receiptStorage.getReceiptsByHash(header.hash), JsonRpcError.ReceiptNotAvailable)
      body     <- EitherT.fromOptionF(blocksReader.getBlockBody(header.hash), JsonRpcError.BlockNotFound)
      block     = ObftBlock(header, body)
      logs = for {
               (receipt, receiptIndex) <- receipts.toList.zipWithIndex
               (log, index)            <- receipt.logs.zipWithIndex
               if addresses.isEmpty || addresses.contains(log.loggerAddress)
               if topics.isEmpty || (topics.length <= log.logTopics.length && topicsMatch(topics, log))
             } yield TransactionLogWithRemoved(block, log, index, receiptIndex)
    } yield logs

  private def topicsMatch(topicSearches: Seq[TopicSearch], log: TransactionLogEntry): Boolean =
    topicSearches.zipWithIndex.forall {
      case (OrTopicSearch(values), index) =>
        values.exists { case ExactTopicSearch(value) => log.logTopics(index) == value }
      case (ExactTopicSearch(value), index) => log.logTopics(index) == value
      case (AnythingTopicSearch, _)         => true
    }
}
