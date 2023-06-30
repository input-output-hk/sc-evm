package io.iohk.scevm.ledger

import cats.syntax.all._
import cats.{Applicative, FlatMap, Monad, Show}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.TransactionOutcome.{FailureOutcome, SuccessOutcome}
import io.iohk.scevm.domain.UInt256.BigIntAsUInt256
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.validators.{SignedTransactionError, SignedTransactionValidator}
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.utils.Logger

import TransactionExecution.{StateBeforeFailure, TxsExecutionError}

trait TransactionExecution[F[_]] {
  def executeTransactions(
      signedTransactions: Seq[SignedTransaction],
      world: InMemoryWorldState,
      accumGas: BigInt = 0,
      accumReceipts: Seq[Receipt] = Nil
  ): F[Either[TxsExecutionError, BlockExecutionResult]]
}

class TransactionExecutionImpl[F[_]: Monad](
    vm: VM[F, WorldType, StorageType],
    signedTxValidator: SignedTransactionValidator,
    rewardAddressProvider: RewardAddressProvider,
    blockchainConfig: BlockchainConfig
) extends TransactionExecution[F]
    with Logger {

  // scalastyle:off method.length

  /** This functions executes all the signed transactions from a block (till one of those executions fails)
    *
    * @param signedTransactions from the block that are left to execute
    * @param world              that will be updated by the execution of the signedTransactions
    * @param accumGas           , accumulated gas of the previoulsy executed transactions of the same block
    * @param accumReceipts      , accumulated receipts of the previoulsy executed transactions of the same block
    * @return a BlockResult if the execution of all the transactions in the block was successful or a BlockExecutionError
    *         if one of them failed
    */
  final def executeTransactions(
      signedTransactions: Seq[SignedTransaction],
      world: InMemoryWorldState,
      accumGas: BigInt = 0,
      accumReceipts: Seq[Receipt] = Nil
  ): F[Either[TxsExecutionError, BlockExecutionResult]] =
    FlatMap[F].tailRecM((signedTransactions, world, accumGas, accumReceipts)) {
      case (signedTransactions, world, accumGas, accumReceipts) =>
        signedTransactions match {
          case Seq(stx, otherStxs @ _*) =>
            val validatedStx = validateTransaction(stx, world, accumGas)

            validatedStx match {
              case Right((account, address)) =>
                executeTransaction(stx, address, world.saveAccount(address, account))(blockchainConfig)
                  .map { result =>
                    val receipt = generateTransactionReceipt(stx, result, accumGas)

                    Left(
                      (otherStxs, result.worldState, receipt.cumulativeGasUsed, accumReceipts :+ receipt)
                    )
                  }
              case Left(error) =>
                Applicative[F].pure(
                  Right(
                    Left(
                      TxsExecutionError(
                        stx,
                        StateBeforeFailure(world, accumGas, accumReceipts),
                        error.toString
                      )
                    )
                  )
                )
            }
          case _ =>
            Applicative[F].pure(
              Right(Right(BlockExecutionResult(worldState = world, gasUsed = accumGas, receipts = accumReceipts)))
            )
        }
    }

  private def validateTransaction(
      stx: SignedTransaction,
      world: InMemoryWorldState,
      accumGas: BigInt
  ): Either[SignedTransactionError, (Account, Address)] = {
    val senderAddress = SignedTransaction.getSender(stx)(blockchainConfig.chainId)

    val accountDataOpt = senderAddress
      .map { address =>
        world
          .getAccount(address)
          .map(a => (a, address))
          .getOrElse((Account.empty(blockchainConfig.genesisData.accountStartNonce), address))
      }
      .toRight(SignedTransactionError.TransactionSignatureError)

    for {
      accountData <- accountDataOpt
      _ <-
        signedTxValidator.validate(
          stx,
          accountData._1,
          world.blockContext.number,
          world.blockContext.gasLimit,
          accumGas
        )(
          blockchainConfig
        )
    } yield accountData
  }

  private def generateTransactionReceipt(stx: SignedTransaction, result: TxResult, accumGas: BigInt): Receipt = {
    val transactionOutcome = if (result.vmError.isDefined) FailureOutcome else SuccessOutcome

    val receiptBody = ReceiptBody(
      postTransactionStateHash = transactionOutcome,
      cumulativeGasUsed = accumGas + result.gasUsed,
      logsBloomFilter = BloomFilter.create(result.logs),
      logs = result.logs
    )
    val receipt = stx.transaction match {
      case _: LegacyTransaction => LegacyReceipt(receiptBody)
      case _: TransactionType01 => Type01Receipt(receiptBody)
      case _: TransactionType02 => Type02Receipt(receiptBody)
    }

    log.debug(show"Receipt generated for ${stx.hash}")
    receipt
  }

  /** Increments account nonce by 1 stated in YP equation (69) and
    * Pays the upfront Tx gas calculated as TxGasPrice * TxGasLimit from balance. YP equation (68)
    */
  private def updateSenderAccountBeforeExecution(
      stx: SignedTransaction,
      senderAddress: Address,
      worldState: InMemoryWorldState
  ): InMemoryWorldState = {
    val account = worldState.getGuaranteedAccount(senderAddress)
    worldState.saveAccount(
      senderAddress,
      account.increaseBalance(-stx.transaction.calculateUpfrontGas).increaseNonceOnce
    )
  }

  private def runVM(
      stx: SignedTransaction,
      senderAddress: Address,
      world: InMemoryWorldState
  )(implicit blockchainConfig: BlockchainConfig): F[PR] = {
    val evmConfig   = EvmConfig.forBlock(world.blockContext.number, blockchainConfig)
    val context: PC = ProgramContext(stx.transaction, world.blockContext, senderAddress, world, evmConfig)
    vm.run(context)
  }

  /** Calculate total gas to be refunded
    * See YP, eq (72)
    */
  private def calcTotalGasToRefund(stx: SignedTransaction, result: PR): BigInt =
    result.error.map(_.useWholeGas) match {
      case Some(true)  => 0
      case Some(false) => result.gasRemaining
      case None =>
        val gasUsed = stx.transaction.gasLimit - result.gasRemaining
        result.gasRemaining + (gasUsed / 2).min(result.gasRefund)
    }

  private def pay(address: Address, value: UInt256, withTouch: Boolean)(
      world: InMemoryWorldState
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldState =
    if (world.isZeroValueTransferToNonExistentAccount(address, value)) {
      world
    } else {
      val savedWorld = world.increaseAccountBalance(address, value)
      if (withTouch) savedWorld.touchAccounts(address) else savedWorld
    }

  /** Delete all accounts (that appear in SUICIDE list). YP eq (78).
    * The contract storage should be cleared during pruning as nodes could be used in other tries.
    * The contract code is also not deleted as there can be contracts with the exact same code, making it risky to delete
    * the code of an account in case it is shared with another one.
    *
    * @param addressesToDelete
    * @param worldState
    * @return a worldState equal worldState except that the accounts from addressesToDelete are deleted
    */
  private def deleteAccounts(addressesToDelete: Set[Address])(
      worldState: InMemoryWorldState
  ): InMemoryWorldState =
    addressesToDelete.foldLeft(worldState) { case (world, address) => world.deleteAccount(address) }

  /** EIP161 - State trie clearing
    * Delete all accounts that have been touched (involved in any potentially state-changing operation) during transaction execution.
    *
    * All potentially state-changing operation are:
    * Account is the target or refund of a SUICIDE operation for zero or more value;
    * Account is the source or destination of a CALL operation or message-call transaction transferring zero or more value;
    * Account is the source or newly-creation of a CREATE operation or contract-creation transaction endowing zero or more value;
    * as the block author ("miner") it is recipient of block-rewards or transaction-fees of zero or more.
    *
    * Deletion of touched account should be executed immediately following the execution of the suicide list
    *
    * @param world world after execution of all potentially state-changing operations
    * @return a worldState equal worldState except that the accounts touched during execution are deleted and touched
    *         Set is cleared
    */
  private def deleteEmptyTouchedAccounts(
      world: InMemoryWorldState
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldState = {
    def deleteEmptyAccount(world: InMemoryWorldState, address: Address) =
      if (world.getAccount(address).exists(_.isEmpty(blockchainConfig.genesisData.accountStartNonce)))
        world.deleteAccount(address)
      else
        world

    world.touchedAccounts
      .foldLeft(world)(deleteEmptyAccount)
      .clearTouchedAccounts
  }

  private def executeTransaction(
      stx: SignedTransaction,
      senderAddress: Address,
      world: InMemoryWorldState
  )(implicit blockchainConfig: BlockchainConfig): F[TxResult] = {
    log.debug(show"Transaction ${stx.hash} execution start")
    // Note that this does not implement eip-1559
    val gasPrice = UInt256(stx.transaction.maxFeePerGas)
    val gasLimit = stx.transaction.gasLimit

    val checkpointWorldState = updateSenderAccountBeforeExecution(stx, senderAddress, world)

    runVM(stx, senderAddress, checkpointWorldState).map { result =>
      val resultWithErrorHandling: PR =
        if (result.error.isDefined) {
          //Rollback to the world before transfer was done if an error happened
          result.copy(world = checkpointWorldState, addressesToDelete = Set.empty, logs = Nil)
        } else
          result

      val totalGasToRefund            = calcTotalGasToRefund(stx, resultWithErrorHandling)
      val executionGasToPayToProducer = gasLimit - totalGasToRefund

      val refundGasFn      = pay(senderAddress, (totalGasToRefund * gasPrice).toUInt256, withTouch = false) _
      val gasRewardAddress = rewardAddressProvider.getRewardAddress(world.blockContext)
      val payMinerForGasFn =
        pay(gasRewardAddress, (executionGasToPayToProducer * gasPrice).toUInt256, withTouch = true) _

      val worldAfterPayments = refundGasFn.andThen(payMinerForGasFn)(resultWithErrorHandling.world)

      val deleteAccountsFn        = deleteAccounts(resultWithErrorHandling.addressesToDelete) _
      val deleteTouchedAccountsFn = deleteEmptyTouchedAccounts _
      val persistStateFn          = InMemoryWorldState.persistState _

      val world2 = (deleteAccountsFn.andThen(deleteTouchedAccountsFn).andThen(persistStateFn))(worldAfterPayments)

      log.debug(show"""Transaction ${stx.hash} execution end. Summary:
           | - Error: ${result.error.map { e =>
        show"error=$e, useWholeGas=${e.useWholeGas}, returned data: ${result.returnData.utf8String}"
      }}.
           | - Logs: ${resultWithErrorHandling.logs.map(_.toString)}.
           | - Returned data: ${Hex.toHexString(result.returnData)}.
           | - Total Gas to Refund: $totalGasToRefund.
           | - Execution gas paid to producer: $executionGasToPayToProducer""".stripMargin)

      TxResult(world2, executionGasToPayToProducer, resultWithErrorHandling.logs, result.returnData, result.error)
    }
  }

}

object TransactionExecution {

  final case class TxsExecutionError(
      stx: SignedTransaction,
      stateBeforeError: StateBeforeFailure,
      reason: String
  )

  object TxsExecutionError {
    implicit val show: Show[TxsExecutionError] = cats.derived.semiauto.show
  }

  final case class StateBeforeFailure(
      worldState: InMemoryWorldState,
      accumGas: BigInt,
      accumReceipts: Seq[Receipt]
  )

  object StateBeforeFailure {
    implicit val show: Show[StateBeforeFailure] = cats.derived.semiauto.show
  }
}
