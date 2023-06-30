package io.iohk.scevm.exec.validators

import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.config.EvmConfig

object SignedTransactionValidatorImpl extends SignedTransactionValidator {

  import SignedTransactionError._

  // see EYP Appendix (285)
  val secp256k1n: BigInt = BigInt("115792089237316195423570985008687907852837564279074904382605163141518161494337")

  def validate(
      stx: SignedTransaction,
      senderAccount: Account,
      blockNumber: BlockNumber,
      gasLimit: BigInt,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    for {
      _ <- validateForMempool(stx, senderAccount, blockNumber, gasLimit, accumGasUsed)
      _ <- validateNonceEqualsSender(stx, senderAccount.nonce)
    } yield SignedTransactionValid

  def validateForMempool(
      stx: SignedTransaction,
      senderAccount: Account,
      blockNumber: BlockNumber,
      gasLimit: BigInt,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] = {
    val upfrontGasCost = calculateUpfrontCost(stx.transaction)

    for {
      _ <- checkSyntacticValidity(stx)
      _ <- validateSignature(stx, blockNumber)
      _ <- validateNonceGreaterEqualsSender(stx, senderAccount.nonce)
      _ <- validateGasLimitEnoughForIntrinsicGas(stx, blockNumber)
      _ <- validateAccountHasEnoughGasToPayUpfrontCost(senderAccount.balance, upfrontGasCost)
      _ <- validateBlockHasEnoughGasLimitForTx(stx, accumGasUsed, gasLimit)
    } yield SignedTransactionValid
  }

  /** v0 ≡ Tg (Tx gas limit) * Tp (Tx gas price) + Tv (Tx value). See YP equation number (65)
    *
    * @param tx Target transaction
    * @return Upfront cost
    */
  private def calculateUpfrontCost(tx: Transaction): UInt256 = UInt256(tx.calculateUpfrontGas + tx.value)

  /** Validates if the transaction is syntactically valid (lengths of the transaction fields are correct)
    *
    * @param stx Transaction to validate
    * @return Either the validated transaction or TransactionSyntaxError if an error was detected
    */
  private def checkSyntacticValidity(stx: SignedTransaction): Either[SignedTransactionError, SignedTransactionValid] = {
    import Transaction._
    import stx._
    import stx.transaction._

    val nonceUpperBound = BigInt(2).pow(8 * NonceLength)
    val gasUpperBound   = BigInt(2).pow(8 * GasLength)
    val valueUpperBound = BigInt(2).pow(8 * ValueLength)
    val rUpperBound     = BigInt(2).pow(8 * ECDSASignature.RLength)
    val sUpperBound     = BigInt(2).pow(8 * ECDSASignature.SLength)

    if (nonce.value >= nonceUpperBound)
      Left(TransactionSyntaxError(s"Invalid nonce: ${nonce.value} has to be less than $nonceUpperBound"))
    else if (gasLimit >= gasUpperBound)
      Left(TransactionSyntaxError(s"Invalid gasLimit: $gasLimit has to be less than $gasUpperBound"))
    else if (value >= valueUpperBound)
      Left(TransactionSyntaxError(s"Invalid value: $value has to be less than $valueUpperBound"))
    else if (signature.r >= rUpperBound)
      Left(TransactionSyntaxError(s"Invalid signatureRandom: ${signature.r} has to be less than $rUpperBound"))
    else if (signature.s >= sUpperBound)
      Left(TransactionSyntaxError(s"Invalid signature: ${signature.s} has to be less than $sUpperBound"))
    else
      stx.transaction match {
        case t: LegacyTransaction =>
          if (t.gasPrice >= gasUpperBound)
            Left(TransactionSyntaxError(s"Invalid gasPrice: ${t.gasPrice} has to be less than $gasUpperBound"))
          else Right(SignedTransactionValid)
        case t: TransactionType01 =>
          if (t.gasPrice >= gasUpperBound)
            Left(TransactionSyntaxError(s"Invalid gasPrice: ${t.gasPrice} has to be less than $gasUpperBound"))
          else Right(SignedTransactionValid)
        case t: TransactionType02 =>
          if (t.maxPriorityFeePerGas >= gasUpperBound)
            Left(
              TransactionSyntaxError(
                s"Invalid maxPriorityFeePerGas: ${t.maxPriorityFeePerGas} has to be less than $gasUpperBound"
              )
            )
          else if (t.maxFeePerGas >= gasUpperBound)
            Left(TransactionSyntaxError(s"Invalid maxFeePerGas: ${t.maxFeePerGas} has to be less than $gasUpperBound"))
          else if (t.maxFeePerGas < t.maxPriorityFeePerGas)
            Left(
              TransactionSyntaxError(
                s"Invalid gas fees: maxPriorityFeePerGas (${t.maxPriorityFeePerGas}) has to be less than maxFeePerGas (${t.maxFeePerGas})"
              )
            )
          else Right(SignedTransactionValid)
      }
  }

  /** Validates if the transaction signature is valid as stated in appendix F in YP
    *
    * @param stx                  Transaction to validate
    * @param blockNumber          Number of the block for this transaction
    * @return Either the validated transaction or TransactionSignatureError if an error was detected
    */
  private def validateSignature(
      stx: SignedTransaction,
      blockNumber: BlockNumber
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] = {
    val r = stx.signature.r
    val s = stx.signature.s

    val beforeHomestead = blockNumber < blockchainConfig.forkBlockNumbers.homesteadBlockNumber
    val beforeEIP155    = blockNumber < blockchainConfig.forkBlockNumbers.spuriousDragonBlockNumber

    val validR             = r > 0 && r < secp256k1n
    val validS             = s > 0 && s < (if (beforeHomestead) secp256k1n else secp256k1n / 2)
    val validSigningSchema = if (beforeEIP155) !stx.isChainSpecific else true

    if (validR && validS && validSigningSchema) Right(SignedTransactionValid)
    else Left(TransactionSignatureError)
  }

  /** Validates if the transaction nonce matches current sender account's nonce
    *
    * @param stx Transaction to validate
    * @param senderNonce Nonce of the sender of the transaction
    * @return Either the validated transaction or a TransactionNonceError
    */
  private def validateNonceEqualsSender(
      stx: SignedTransaction,
      senderNonce: Nonce
  ): Either[SignedTransactionError, SignedTransactionValid] =
    if (senderNonce == stx.transaction.nonce) Right(SignedTransactionValid)
    else Left(TransactionNonceError(stx.transaction.nonce, senderNonce))

  /** Validates if the transaction nonce is greater or equal to the sender account's nonce.
    * Transactions with higher nonce are allowed to be added to the mempool.
    *
    * @param stx Transaction to validate
    * @param senderNonce Nonce of the sender of the transaction
    * @return Either the validated transaction or a TransactionNonceError
    */
  private def validateNonceGreaterEqualsSender(
      stx: SignedTransaction,
      senderNonce: Nonce
  ): Either[SignedTransactionError, SignedTransactionValid] =
    if (senderNonce <= stx.transaction.nonce) Right(SignedTransactionValid)
    else Left(TransactionNonceError(stx.transaction.nonce, senderNonce))

  /** Validates the gas limit is no smaller than the intrinsic gas used by the transaction.
    *
    * @param stx Transaction to validate
    * @param blockHeaderNumber Number of the block where the stx transaction was included
    * @return Either the validated transaction or a TransactionNotEnoughGasForIntrinsicError
    */
  private def validateGasLimitEnoughForIntrinsicGas(
      stx: SignedTransaction,
      blockHeaderNumber: BlockNumber
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] = {
    import stx.transaction
    val config = EvmConfig.forBlock(blockHeaderNumber, blockchainConfig)
    val txIntrinsicGas = config.calcTransactionIntrinsicGas(
      transaction.payload,
      transaction.isContractInit,
      Transaction.accessList(transaction)
    )
    if (stx.transaction.gasLimit >= txIntrinsicGas) Right(SignedTransactionValid)
    else Left(TransactionNotEnoughGasForIntrinsicError(stx.transaction.gasLimit, txIntrinsicGas))
  }

  /** Validates the sender account balance contains at least the cost required in up-front payment.
    *
    * @param senderBalance Balance of the sender of the tx
    * @param upfrontCost Upfront cost of the transaction tx
    * @return Either the validated transaction or a TransactionSenderCantPayUpfrontCostError
    */
  private def validateAccountHasEnoughGasToPayUpfrontCost(
      senderBalance: UInt256,
      upfrontCost: UInt256
  ): Either[SignedTransactionError, SignedTransactionValid] =
    if (senderBalance >= upfrontCost) Right(SignedTransactionValid)
    else Left(TransactionSenderCantPayUpfrontCostError(upfrontCost, senderBalance))

  /** The sum of the transaction’s gas limit and the gas utilised in this block prior must be no greater than the
    * block’s gasLimit
    *
    * @param stx           Transaction to validate
    * @param accumGasUsed Gas spent within tx container block prior executing stx
    * @param blockGasLimit Block gas limit
    * @return Either the validated transaction or a TransactionGasLimitTooBigError
    */
  private def validateBlockHasEnoughGasLimitForTx(
      stx: SignedTransaction,
      accumGasUsed: BigInt,
      blockGasLimit: BigInt
  ): Either[SignedTransactionError, SignedTransactionValid] =
    if (stx.transaction.gasLimit + accumGasUsed <= blockGasLimit) Right(SignedTransactionValid)
    else Left(TransactionGasLimitTooBigError(stx.transaction.gasLimit, accumGasUsed, blockGasLimit))
}
