package io.iohk.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.iohk.ethereum.rlp
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.SignedTransaction.SignedTransactionRLPImplicits.SignedTransactionEnc
import io.iohk.scevm.domain.{Address, LegacyTransaction, Nonce, SidechainPrivateKey, SignedTransaction, Token}
import io.iohk.scevm.sidechain.BridgeContract
import io.iohk.scevm.sidechain.transactions.OutgoingTxRecipient
import io.iohk.scevm.solidity.{SolidityAbi, SolidityAbiEncoder}

object CreateLockTxCommand {
  final private case class Params(
      recipient: OutgoingTxRecipient,
      amount: Token,
      nonce: Nonce,
      gasPrice: BigInt,
      gasLimit: BigInt,
      privateKey: Option[SidechainPrivateKey]
  )

  private val outgoingTxRecipientOpts: Opts[OutgoingTxRecipient] = Opts
    .argument[String](
      "Recipient of the transaction on the main chain as hex"
    )
    .mapValidated(str => OutgoingTxRecipient.decode(str).toValidatedNel)

  private val amountOpts: Opts[Token] =
    Opts
      .argument[BigInt]("Transaction amount in fuel tokens")
      .map(Token(_))

  private val gasPriceOpts: Opts[BigInt] = Opts
    .option[BigInt]("gasPrice", "GasPrice for the transaction")
    .withDefault(0L)

  private val gasLimitOpts: Opts[BigInt] = Opts
    .option[BigInt](
      "gasLimit",
      "GasLimit for the transaction. Default value is 150k. It was calculated with the use of transaction simulator."
    )
    .withDefault(150_000) // scalastyle:ignore

  private val nonceOpt: Opts[Nonce] = Opts
    .option[Int]("nonce", "nonce - tip: use eth_getTransactionCount")
    .map(Nonce(_))

  private val prvKeyBytes = Opts
    .option[String]("private-key", "ECDSA private key as hex to sign the transaction")
    .mapValidated(str => SidechainPrivateKey.fromHex(str).toValidatedNel)
    .orNone

  private val opts: Opts[Params] = Opts.subcommand(
    "create-lock-tx",
    "Create and optionally sign lock transaction (used to transfer tokens from the SC to MC)"
  ) {
    (outgoingTxRecipientOpts, amountOpts, nonceOpt, gasPriceOpts, gasLimitOpts, prvKeyBytes).mapN(Params.apply)
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map { case Params(recipient, amount, nonce, gasPrice, gasLimit, privateKey) =>
      implicit val recipientSolidityEncoder: SolidityAbiEncoder[OutgoingTxRecipient] = OutgoingTxRecipient.deriving
      val bridgePayload                                                              = SolidityAbi.solidityCall(BridgeContract.Methods.Lock, recipient)
      val transaction = LegacyTransaction(
        nonce = nonce,
        gasPrice = gasPrice,
        gasLimit = gasLimit,
        receivingAddress = Address("0x696f686b2e6d616d626100000000000000000000"),
        value = amount.value,
        payload = bridgePayload
      )

      val output = privateKey match {
        case Some(value) =>
          val signedTx = SignedTransaction.sign(transaction, value, None).toRLPEncodable
          val bytes    = rlp.encode(signedTx)
          Hex.toHexString(bytes)
        case None =>
          val rlpEncodable = SignedTransaction.legacyTxRlpContent(transaction)
          val bytes        = rlp.encode(rlpEncodable)
          Hex.toHexString(bytes)
      }
      // scalastyle:off regex
      IO.println(output).as(ExitCode.Success)
    // scalastyle:on regex
    }

}
