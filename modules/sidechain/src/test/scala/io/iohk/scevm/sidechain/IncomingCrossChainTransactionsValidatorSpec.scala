package io.iohk.scevm.sidechain

import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.{
  Address,
  ObftHeader,
  ReceiptBody,
  Token,
  TransactionLogEntry,
  TransactionOutcome,
  Type02Receipt,
  UInt256
}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.sidechain.BridgeContract.{
  IncomingTransactionHandledEvent,
  IncomingTransactionId,
  IncomingTransactionResult
}
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction
import io.iohk.scevm.sidechain.consensus.IncomingCrossChainTransactionsValidator
import io.iohk.scevm.solidity.SolidityAbiEncoder
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience}
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainTxHash}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class IncomingCrossChainTransactionsValidatorSpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  val bridgeAddress: Address     = Address("aabbccddeeff00112233445566778899001122")
  val otherAddress: Address      = Address("00000000000000112233445566778899000000")
  val recipient1Address: Address = Address("00000000000000000000000000000000000011")
  val recipient2Address: Address = Address("00000000000000000000000000000000000022")

  val txHash1: MainchainTxHash       = MainchainTxHash(ByteString(Array.fill(32)(1.toByte)))
  val txHash2: MainchainTxHash       = MainchainTxHash(ByteString(Array.fill(32)(3.toByte)))
  val missingTxHash: MainchainTxHash = MainchainTxHash(ByteString(Array.fill(32)(2.toByte)))

  val incomingTransactionId1: IncomingTransactionId = IncomingTransactionId.fromTxHash(txHash1)
  val incomingTxEvent1: IncomingTransactionHandledEvent =
    IncomingTransactionHandledEvent(incomingTransactionId1, recipient1Address, 3330, IncomingTransactionResult.Success)
  val incomingTxEvent1AbiBytes: ByteString =
    BridgeContract.IncomingTransactionHandledEventEncoder.encode(incomingTxEvent1)

  val incomingTransactionId2: IncomingTransactionId = IncomingTransactionId.fromTxHash(txHash2)
  val incomingTxEvent2: IncomingTransactionHandledEvent =
    IncomingTransactionHandledEvent(incomingTransactionId2, recipient2Address, 1110, IncomingTransactionResult.Success)
  val incomingTxEvent2AbiBytes: ByteString =
    BridgeContract.IncomingTransactionHandledEventEncoder.encode(incomingTxEvent2)

  val missingIncomingTransactionId: IncomingTransactionId = IncomingTransactionId.fromTxHash(missingTxHash)
  val eventForMissingTx: IncomingTransactionHandledEvent =
    IncomingTransactionHandledEvent(
      missingIncomingTransactionId,
      recipient1Address,
      666,
      IncomingTransactionResult.Success
    )
  val eventForMissingTxAbiBytes: ByteString =
    BridgeContract.IncomingTransactionHandledEventEncoder.encode(eventForMissingTx)

  val incomingTxEventForWrongValue: IncomingTransactionHandledEvent = incomingTxEvent1.copy(amount = 999999)
  val incomingTxEventForWrongValueBytes: ByteString =
    BridgeContract.IncomingTransactionHandledEventEncoder.encode(incomingTxEventForWrongValue)

  val mainchainTransactions: List[ValidIncomingCrossChainTransaction] = List(
    ValidIncomingCrossChainTransaction(recipient1Address, Token(333), txHash1, MainchainBlockNumber(38)),
    ValidIncomingCrossChainTransaction(recipient2Address, Token(111), txHash2, MainchainBlockNumber(38))
  )

  val xcTransactionTopics: Seq[ByteString] = Seq(BridgeContract.IncomingTransactionHandledEventTopic)
  val xcEvent1Topics: Seq[ByteString] = Seq(
    BridgeContract.IncomingTransactionHandledEventTopic,
    SolidityAbiEncoder[Address].encode(incomingTxEvent1.recipient)
  )
  val xcEvent2Topics: Seq[ByteString] = Seq(
    BridgeContract.IncomingTransactionHandledEventTopic,
    SolidityAbiEncoder[Address].encode(incomingTxEvent2.recipient)
  )
  val xcEventForWrongValueTopics: Seq[ByteString] = xcEvent1Topics
  val xcEventMissingTxTopics: Seq[ByteString] = Seq(
    BridgeContract.IncomingTransactionHandledEventTopic,
    SolidityAbiEncoder[Address].encode(eventForMissingTx.recipient)
  )

  val otherTopic: ByteString = ByteString("something_else")

  val header: ObftHeader      = BlockGenerators.obftBlockHeaderGen.sample.get
  val initialWorld: WorldType = TestInMemoryWorldState.worldStateGen.sample.get

  def makeExecutionResult(logEntries: TransactionLogEntry*): BlockExecutionResult = {
    val postWorld = TestInMemoryWorldState.worldStateGen.sample.get
    val receipts = Seq(
      Type02Receipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, Seq.empty)),
      Type02Receipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, logEntries))
    )
    BlockExecutionResult(postWorld, 0, receipts)
  }

  val txProvider: IncomingCrossChainTransactionsProvider[IO] =
    (w: WorldType) =>
      if (w == initialWorld) IO.pure(mainchainTransactions)
      else
        IO.raiseError(
          new Exception(show"Unexpected parameter $w, expected ${header.slotNumber}, $header, $initialWorld")
        )

  val validator = new IncomingCrossChainTransactionsValidator[IO](txProvider, bridgeAddress, UInt256(10))

  "fails if log entries can't be decoded" in {
    val executionResult =
      makeExecutionResult(TransactionLogEntry(bridgeAddress, xcEvent1Topics, ByteString("abcdefghi")))

    val result = validator
      .validate(initialWorld, header, executionResult)
      .ioValue

    result.swap.value.message should startWith("Invalid IncomingTransactionHandledEvent data:")
  }

  "fails if any transaction from log events can't be found in mainchain transaction" in {
    val executionResult = makeExecutionResult(
      TransactionLogEntry(bridgeAddress, xcEvent1Topics, incomingTxEvent1AbiBytes),
      TransactionLogEntry(bridgeAddress, xcEvent2Topics, incomingTxEvent2AbiBytes),
      TransactionLogEntry(bridgeAddress, xcEventMissingTxTopics, eventForMissingTxAbiBytes)
    )

    val result = validator
      .validate(initialWorld, header, executionResult)
      .ioValue

    result.swap.value.message should startWith(
      "The block transactions without a match: [" +
        "(2,MainchainTxHash(0202020202020202020202020202020202020202020202020202020202020202))], " +
        "mainchain transactions without a match []"
    )
  }

  "fails if any log entry doesn't match transaction" in {
    val executionResult = makeExecutionResult(
      TransactionLogEntry(bridgeAddress, xcEventForWrongValueTopics, incomingTxEventForWrongValueBytes),
      TransactionLogEntry(bridgeAddress, xcEvent2Topics, incomingTxEvent2AbiBytes)
    )

    val result = validator
      .validate(initialWorld, header, executionResult)
      .ioValue

    result.swap.value.message should startWith(
      "The block transactions without a match: [" +
        "(0,MainchainTxHash(0101010101010101010101010101010101010101010101010101010101010101))], " +
        "mainchain transactions without a match [(0,MainchainTxHash(0101010101010101010101010101010101010101010101010101010101010101))]"
    )
  }

  "fails if order of transactions is different" in {
    val executionResult = makeExecutionResult(
      TransactionLogEntry(bridgeAddress, xcEvent2Topics, incomingTxEvent2AbiBytes),
      TransactionLogEntry(bridgeAddress, xcEvent1Topics, incomingTxEvent1AbiBytes)
    )

    val result = validator
      .validate(initialWorld, header, executionResult)
      .ioValue

    result.swap.value.message should startWith(
      "The block transactions without a match: [" +
        "(0,MainchainTxHash(0303030303030303030303030303030303030303030303030303030303030303)), " +
        "(1,MainchainTxHash(0101010101010101010101010101010101010101010101010101010101010101))], " +
        "mainchain transactions without a match [" +
        "(0,MainchainTxHash(0101010101010101010101010101010101010101010101010101010101010101)), " +
        "(1,MainchainTxHash(0303030303030303030303030303030303030303030303030303030303030303))]"
    )
  }

  "succeeds if all cross-chain transactions are found and match" in {
    val executionResult = makeExecutionResult(
      TransactionLogEntry(otherAddress, Seq(ByteString("xyz")), ByteString("would fail decoding")),
      TransactionLogEntry(bridgeAddress, Seq(otherTopic), ByteString("would fail decoding")),
      TransactionLogEntry(bridgeAddress, xcEvent1Topics, incomingTxEvent1AbiBytes),
      TransactionLogEntry(bridgeAddress, xcEvent2Topics, incomingTxEvent2AbiBytes)
    )

    val result = validator
      .validate(initialWorld, header, executionResult)
      .ioValue
    result.value shouldBe ()
  }

  "succeeds if there are mainchain transactions after matching sidechain transactions" in {
    val executionResult = makeExecutionResult(
      TransactionLogEntry(bridgeAddress, xcEvent1Topics, incomingTxEvent1AbiBytes)
    )

    val result = validator
      .validate(initialWorld, header, executionResult)
      .ioValue
    result.value shouldBe ()
  }
}
