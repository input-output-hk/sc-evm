package io.iohk.cli

import cats.data.Validated
import cats.effect.std.Console
import cats.effect.{ExitCode, IO}
import cats.implicits._
import com.monovore.decline.Opts
import io.circe.generic.semiauto._
import io.circe.literal.JsonStringContext
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Json, Printer}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.jsonRpcClient.JsonRpcClient
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, Token, TransactionHash}
import org.bouncycastle.jcajce.provider.digest.Keccak
import sttp.model.Uri

object SearchIncomingTransactions {
  final case class Params(
      scEvmUrl: Uri,
      recipient: Option[Address],
      from: BlockNumber,
      to: Option[BlockNumber],
      txHash: Option[TransactionHash],
      outputFormat: OutputFormat,
      stopOnFirstMatch: Boolean,
      verbose: Boolean
  )

  sealed trait OutputFormat

  case object JsonFormat extends OutputFormat

  case object HumanReadable extends OutputFormat

  private def opts(
      configDefaultUrl: Option[Uri]
  ): Opts[Params] = {
    val from: Opts[BlockNumber] = CommonOpts.blockNumberOpt("from", "lowest block number to search in")
    val to: Opts[Option[BlockNumber]] = CommonOpts
      .blockNumberOpt("to", "highest block number to search in. If not set it will use the latest block.")
      .orNone
    val txHash: Opts[Option[TransactionHash]] = Opts
      .option[String](
        "tx-hash",
        "Optional filter for the transaction hash. Implies --stop-on-first-match"
      )
      .mapValidated(str => Hex.decode(str).map(TransactionHash.apply).toValidatedNel)
      .orNone
    val outputFormat: Opts[OutputFormat] = Opts
      .option[String]("output-format", "Output format: 'human-readable' or 'json'.")
      .mapValidated {
        case "human-readable" => Validated.validNel(HumanReadable)
        case "json"           => Validated.validNel(JsonFormat)
        case other            => Validated.invalidNel(show"Invalid output format: $other")
      }
      .withDefault(HumanReadable)
    val stopOnFirstMatch: Opts[Boolean] =
      Opts.flag("stop-on-first-match", "Stop searching as soon as a transaction is found").orFalse

    Opts.subcommand(
      "search-incoming-txs",
      "Searches sidechain ledger for transactions from main chain to sidechain." +
        "Reads the chain backwards and prints transactions that match the filters."
    ) {
      (
        CommonOpts.nodeUrl(configDefaultUrl),
        CommonOpts.recipientFilter,
        from,
        to,
        txHash,
        outputFormat,
        stopOnFirstMatch,
        CommonOpts.verbose
      ).mapN(Params)
    }
  }

  def apply(configDefaultUrl: => Option[Uri]): Opts[IO[ExitCode]] =
    opts(configDefaultUrl).map {
      case Params(scEvmUrl, recipient, from, maybeTo, txHash, outputFormat, stopOnFirstMatch, verbose) =>
        JsonRpcClient.asResource[IO](scEvmUrl, verbose).use { implicit rpcClient =>
          implicit val console: Console[IO] = Console.make[IO]
          for {
            to               <- maybeTo.fold(getLatestBlockNumber)(IO.pure)
            events            = streamUnlockEvents(from, to, stopOnFirstMatch, txHash, recipient)
            transactionCount <- events.evalTap(printEvent(outputFormat)).compile.count
            _                <- console.errorln(show"transactions found: $transactionCount")
          } yield ExitCode.Success
        }
    }

  def getLatestBlockNumber(implicit rpcClient: JsonRpcClient[IO]): IO[BlockNumber] = {
    final case class BlockNumberResponse(number: BlockNumber)
    implicit val blockNumberResponseDecoder: Decoder[BlockNumberResponse] = deriveDecoder
    rpcClient
      .callEndpoint[BlockNumberResponse]("eth_getBlockByNumber", List("latest".asJson, json"false"))
      .map(_.number)
  }

  private val ITERATION_STEP: BigInt = 100

  private def streamUnlockEvents(
      from: BlockNumber,
      to: BlockNumber,
      stopOnFirstMatch: Boolean,
      txHash: Option[TransactionHash],
      recipient: Option[Address]
  )(implicit jsonRpcClient: JsonRpcClient[IO], console: Console[IO]): fs2.Stream[IO, UnlockEvent] = {
    val ranges = fs2.Stream
      .range(to.value, from.value, -ITERATION_STEP)
      .map(end => ((end - ITERATION_STEP).max(from.value) -> end))
      .evalTap { case (startBlock, endBlock) => printProgress(from.value, to.value, startBlock, endBlock) }

    val responses = ranges
      .evalMap { case (from, to) => getLogs(from, to) }
      .filter(_._2.nonEmpty)

    val events = responses
      .flatMap { case (rawJson, logs) => fs2.Stream.emits(logs).map((rawJson, _)) }
      .map { case (rawJson, log) => UnlockEvent.fromEventData(rawJson, log) }
      .filter(filterByOptions(recipient, txHash))

    if (stopOnFirstMatch || txHash.nonEmpty) events.take(1) else events
  }

  private def printProgress(from: BigInt, to: BigInt, startBlock: BigInt, endBlock: BigInt)(implicit
      console: Console[IO]
  ): IO[Unit] = {
    val barSize  = 40
    val progress = ((barSize * (to - endBlock)).toDouble / (to - from).toDouble).toInt
    val bar      = ("=" * progress + ">").padTo(barSize, '.')
    console.errorln(f"$bar [blocks $startBlock - $endBlock]")
  }

  private def filterByOptions(
      recipient: Option[Address],
      txHash: Option[TransactionHash]
  )(event: UnlockEvent): Boolean =
    recipient.forall(event.recipient == _) && txHash.forall(event.hash == _)

  final case class GetLogsResponse(
      removed: Boolean,
      logIndex: BigInt,
      transactionIndex: BigInt,
      transactionHash: TransactionHash,
      blockHash: BlockHash,
      blockNumber: BlockNumber,
      address: Address,
      data: ByteString,
      topics: Seq[ByteString]
  )

  implicit val getLogsResponseDecoder: Decoder[GetLogsResponse] = deriveDecoder

  private val CONTRACT_ADDRESS = "0x696f686b2e6d616d626100000000000000000000"

  private val UNLOCK_TOPIC =
    "0x" + Hex.toHexString(
      new Keccak.Digest256().digest("IncomingTransactionHandledEvent(bytes,address,uint256,uint8)".getBytes)
    )

  private def getLogs(from: BigInt, to: BigInt)(implicit
      rpcClient: JsonRpcClient[IO]
  ): IO[(Json, List[GetLogsResponse])] =
    rpcClient.callEndpointPreserveJson[List[GetLogsResponse]](
      "eth_getLogs",
      List(json"""{ "fromBlock": $from, "toBlock": $to, "address": $CONTRACT_ADDRESS, "topics": [$UNLOCK_TOPIC] }""")
    )

  final case class UnlockEvent(
      recipient: Address,
      value: Token,
      hash: TransactionHash,
      status: String,
      blockNumber: BlockNumber,
      blockHash: BlockHash,
      rawJson: Json
  )

  object UnlockEvent {
    private val SOLIDITY_HEX_WORD_SIZE = 32
    private val ETH_ADDRESS_HEX_LENGTH = 20 * 2

    def fromEventData(rawJson: Json, data: GetLogsResponse): UnlockEvent = {
      val recipient =
        data.topics(1).drop(2).slice(SOLIDITY_HEX_WORD_SIZE - ETH_ADDRESS_HEX_LENGTH, SOLIDITY_HEX_WORD_SIZE)

      val (value, status, hash) = data.data.grouped(SOLIDITY_HEX_WORD_SIZE).toList match {
        case _ :: value :: statusBytes :: _ :: hash :: _ =>
          val status = BigInt(statusBytes.toArray).toInt match {
            case 0   => "Success"
            case 1   => "Error: the recipient is a contract"
            case err => show"Unknown status - $err"
          }
          (value, status, hash)
        case _ => throw new Exception("Invalid event data structure")
      }

      UnlockEvent(
        recipient = Address(recipient),
        value = Token(BigInt(value.toArray)),
        hash = TransactionHash(hash),
        status = status,
        blockNumber = data.blockNumber,
        blockHash = data.blockHash,
        rawJson = rawJson
      )
    }
  }

  private def printEvent(outputFormat: OutputFormat)(event: UnlockEvent)(implicit console: Console[IO]): IO[Unit] = {
    val output = outputFormat match {
      case JsonFormat =>
        event.rawJson.printWith(Printer.noSpacesSortKeys)
      case HumanReadable =>
        show"""- Transaction in block ${event.blockNumber.value} (${event.blockHash.toHex}):
           |  * hash: ${event.hash.toHex}
           |  * recipient: ${event.recipient}
           |  * value: ${event.value}
           |  * status: ${event.status}
           |""".stripMargin
    }

    //scalastyle:off regex
    console.println(output)
    //scalastyle:on
  }
}
