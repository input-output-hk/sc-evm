package io.iohk.cli

import cats.data.EitherT
import cats.effect.{ExitCode, IO, std}
import cats.implicits._
import com.monovore.decline.Opts
import fs2.text
import io.circe.Json
import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp
import io.iohk.ethereum.utils.Hex
import io.iohk.jsonRpcClient.JsonRpcClient
import io.iohk.scevm.domain.SignedTransaction.SignedTransactionRLPImplicits.SignedTransactionEnc
import io.iohk.scevm.domain.{Address, LegacyTransaction, Nonce, SidechainPrivateKey, SignedTransaction}
import io.iohk.scevm.solidity.SolidityAbi
import sttp.model.Uri

import java.nio.file.Path

object LockFuel {
  private val TokenConversionRate = BigInt(10).pow(9) // scalastyle:ignore

  final case class Params(
      scEvmUrl: Uri,
      recipient: ByteString,
      amount: BigInt,
      gasPrice: BigInt,
      privateKeyFile: String,
      verbose: Boolean
  )
  private val outgoingTxRecipientOpts: Opts[ByteString] = Opts
    .option[String]("recipient", "Recipient of the transaction on the main chain as hex")
    .mapValidated(str => Hex.decode(str).toValidatedNel)

  private val amountOpts: Opts[BigInt] =
    Opts
      .option[BigInt]("amount", "The amount of tokens to send")
      .validate(s"Amount has to be a multiple of $TokenConversionRate")(amount => amount % TokenConversionRate == 0)

  private val gasPriceOpts: Opts[BigInt] = Opts
    .option[BigInt]("gas-price", "Gas price for the transaction")
    .withDefault(0L)

  private val gasLimitOpts: Opts[BigInt] = Opts
    .option[BigInt](
      "gas-limit",
      "Gas limit for the transaction. Default value is 150k. It was calculated with the use of transaction simulator."
    )
    .withDefault(150_000) // scalastyle:ignore

  private val prvKeyBytes = Opts
    .option[String](
      "private-key-file",
      "Path to a file with ECDSA private key as hex, that will be used to sign the transaction"
    )

  private def opts(
      configDefaultUrl: Option[Uri]
  ) =
    Opts.subcommand("lock-sc-token", "Lock sidechain tokens to make transaction from sidechain to the main chain") {
      (
        CommonOpts.nodeUrl(configDefaultUrl),
        outgoingTxRecipientOpts,
        amountOpts,
        gasPriceOpts,
        prvKeyBytes,
        CommonOpts.verbose
      )
        .mapN(Params)
    }

  private def getNonceFromScEvm(address: Address)(implicit jsonRpcClient: JsonRpcClient[IO]): IO[Nonce] =
    jsonRpcClient
      .callEndpoint[String](
        "eth_getTransactionCount",
        List(Json.fromString(address.toString()), Json.fromString("latest"))
      )
      .flatMap { str =>
        IO.delay(Nonce.fromHexUnsafe(str))
      }

  private def submitTransactionToScEvm(rawTx: ByteString)(implicit jsonRpcClient: JsonRpcClient[IO]): IO[Json] =
    jsonRpcClient.callEndpoint[Json]("eth_sendRawTransaction", List(Json.fromString(Hex.toHexString(rawTx))))

  private def scEvmEstimateTransactionGas(
      tx: CallTransaction
  )(implicit jsonRpcClient: JsonRpcClient[IO]): IO[BigInt] = {
    import io.circe.syntax.EncoderOps
    implicit val encoder = io.circe.generic.semiauto.deriveEncoder[CallTransaction]
    jsonRpcClient.callEndpoint[BigInt]("eth_estimateGas", List(tx.asJson))
  }

  final private case class CallTransaction(
      from: String,
      to: String,
      value: BigInt,
      data: ByteString
  )

  private val BridgeContractAddress = Address("0x696f686b2e6d616d626100000000000000000000")

  def apply(
      configDefaultUrl: => Option[Uri]
  ): Opts[IO[ExitCode]] = opts(configDefaultUrl).map {
    case Params(scEvmUrl, recipient, amount, gasPrice, privateKeyFile, verbose) =>
      JsonRpcClient.asResource[IO](scEvmUrl, verbose).use { implicit rpcClient =>
        (for {
          privateKey <- readPrivateKeyFromFile(Path.of(privateKeyFile))
          nonce      <- EitherT.liftF[IO, String, Nonce](getNonceFromScEvm(Address.fromPrivateKey(privateKey)))
          callData    = SolidityAbi.solidityCall("lock", recipient)
          gasLimit <- EitherT.liftF[IO, String, BigInt](
                        scEvmEstimateTransactionGas(
                          CallTransaction(
                            Address.fromPrivateKey(privateKey).toString(),
                            BridgeContractAddress.toString(),
                            amount,
                            callData
                          )
                        )
                      )
          txPayload = createTxPayload(nonce, amount, recipient, gasPrice, gasLimit, privateKey, callData)
          result   <- EitherT.liftF[IO, String, Json](submitTransactionToScEvm(txPayload))
        } yield result).value
          .flatMap {
            case Left(error) => std.Console[IO].errorln(error).as(ExitCode.Error)
            //scalastyle:off regex
            case Right(result) => std.Console[IO].println(result).as(ExitCode.Success)
            //scalastyle:on
          }
      }
  }

  private def readPrivateKeyFromFile(privateKeyFile: Path) =
    EitherT[IO, String, SidechainPrivateKey](
      fs2.io.file
        .readAll[IO](privateKeyFile, 4096) // scalastyle:ignore
        .through(text.utf8.decode)
        .compile
        .toList
        .map(_.mkString.trim())
        .map { f => println(f); f }
        .map(SidechainPrivateKey.fromHex)
    )

  private def createTxPayload(
      nonce: Nonce,
      amount: BigInt,
      recipient: ByteString,
      gasPrice: BigInt,
      gasLimit: BigInt,
      privateKey: SidechainPrivateKey,
      callData: ByteString
  ) = {
    val transaction = LegacyTransaction(
      nonce = nonce,
      gasPrice = gasPrice,
      gasLimit = gasLimit,
      receivingAddress = BridgeContractAddress,
      value = amount,
      payload = callData
    )

    val signedTx = SignedTransaction.sign(transaction, privateKey, None).toRLPEncodable
    val bytes    = rlp.encode(signedTx)
    ByteString(bytes)
  }
}
