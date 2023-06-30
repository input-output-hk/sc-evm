package io.iohk.scevm.sidechain.consensus

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.{
  Address,
  ObftHeader,
  ReceiptBody,
  Slot,
  Token,
  TransactionLogEntry,
  TransactionOutcome,
  Type02Receipt
}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.sidechain.BridgeContract._
import io.iohk.scevm.sidechain.certificate.MerkleRootSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.consensus.OutgoingTransactionSignatureValidatorSpec._
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{
  OutgoingTransaction,
  OutgoingTxId,
  OutgoingTxRecipient,
  TransactionsMerkleTree,
  merkletree
}
import io.iohk.scevm.sidechain.{BridgeContract, SidechainEpochDerivation, SidechainFixtures}
import io.iohk.scevm.solidity.{Bytes32, SolidityAbiEncoder}
import io.iohk.scevm.testing.CryptoGenerators.{KeySet, ecdsaKeyPairGen}
import io.iohk.scevm.testing.{BlockGenerators, CryptoGenerators, IOSupport, NormalPatience}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class OutgoingTransactionSignatureValidatorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with EitherValues
    with IOSupport
    with NormalPatience {

  "invalidates block" when {

    "signature is invalid" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val validator      = makeOutgoingTransactionSignatureValidator(transactions.toList)
      val wrongSignature = rightValidatorCrossChainPrvKey.sign(ByteString("f" * 32))
      val merkleRoot     = TransactionsMerkleTree(None, transactions).rootHash
      val event          = OutgoingTransactionsSignedEvent[ECDSA](rightValidatorAddress, wrongSignature, merkleRoot)
      val logEntryWithInvalidSignature =
        TransactionLogEntry(
          loggerAddress = bridgeAddress,
          logTopics = Seq(BridgeContract.OutgoingTransactionsSignedEventTopic),
          data = OutgoingTransactionsSignedEventEncoder.encode(event)
        )

      val result = validator.validate(world, header, makeExecutionResult(logEntryWithInvalidSignature)).ioValue

      result.left.value.message should startWith("Invalid signature in event")

    }

    "no outgoing transaction was emitted" in {
      val validator = makeOutgoingTransactionSignatureValidator(Seq.empty)
      val nonExistingTransactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val merkleRoot = RootHash(Hex.decodeUnsafe("ff" * 32))
      val signature =
        MerkleRootSigner.sign(None, sidechainParams, nonExistingTransactions)(rightValidatorCrossChainPrvKey).signature
      val event = OutgoingTransactionsSignedEvent(rightValidatorAddress, signature, merkleRoot)
      val logEntryWithInvalidSignature =
        TransactionLogEntry(
          loggerAddress = bridgeAddress,
          logTopics = Seq(BridgeContract.OutgoingTransactionsSignedEventTopic),
          data = OutgoingTransactionsSignedEventEncoder.encode(event)
        )

      val result = validator.validate(world, header, makeExecutionResult(logEntryWithInvalidSignature)).ioValue

      result.left.value.message should startWith("No outgoing transactions exists")
    }

    "the root hash is invalid" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val validator = makeOutgoingTransactionSignatureValidator(transactions.toList)
      val signature =
        MerkleRootSigner.sign(None, sidechainParams, transactions)(rightValidatorCrossChainPrvKey).signature
      val wrongMerkleRoot = RootHash(Hex.decodeUnsafe("ff" * 32))
      val event           = OutgoingTransactionsSignedEvent(rightValidatorAddress, signature, wrongMerkleRoot)
      val logEntryWithInvalidSignature =
        TransactionLogEntry(
          loggerAddress = bridgeAddress,
          logTopics = Seq(BridgeContract.OutgoingTransactionsSignedEventTopic),
          data = OutgoingTransactionsSignedEventEncoder.encode(event)
        )

      val result = validator.validate(world, header, makeExecutionResult(logEntryWithInvalidSignature)).ioValue

      result.left.value.message should startWith("Unexpected merkle root")
    }

    "outgoing transaction was emitted but there is no event during handover phase" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val validator = makeOutgoingTransactionSignatureValidator(transactions.toList)

      val result = validator.validate(world, header, makeExecutionResult()).ioValue

      result.left.value.message should startWith("No signature was emitted")
    }

    "more than one signature event was emitted" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val validator = makeOutgoingTransactionSignatureValidator(transactions.toList)
      val signature = MerkleRootSigner.sign(None, sidechainParams, transactions)(rightValidatorCrossChainPrvKey)
      val event     = OutgoingTransactionsSignedEvent(rightValidatorAddress, signature.signature, signature.rootHash)
      val logEntry =
        TransactionLogEntry(
          loggerAddress = bridgeAddress,
          logTopics = Seq(BridgeContract.OutgoingTransactionsSignedEventTopic),
          data = OutgoingTransactionsSignedEventEncoder.encode(event)
        )

      val result = validator.validate(world, header, makeExecutionResult(logEntry, logEntry)).ioValue

      result.left.value.message should startWith("More than one outgoing transaction signature was emitted")
    }
  }

  "validates block" when {

    "there is no previous root" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val validator = makeOutgoingTransactionSignatureValidator(transactions.toList)
      val signature = MerkleRootSigner.sign(None, sidechainParams, transactions)(rightValidatorCrossChainPrvKey)
      val event     = OutgoingTransactionsSignedEvent(rightValidatorAddress, signature.signature, signature.rootHash)
      val logEntryWithInvalidSignature =
        TransactionLogEntry(
          loggerAddress = bridgeAddress,
          logTopics = Seq(BridgeContract.OutgoingTransactionsSignedEventTopic),
          data = OutgoingTransactionsSignedEventEncoder.encode(event)
        )

      val result = validator.validate(world, header, makeExecutionResult(logEntryWithInvalidSignature)).ioValue

      result shouldBe Right(())
    }

    "there is a previous root" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val prevRoot  = RootHash(Hex.decodeUnsafe("ff" * 32))
      val validator = makeOutgoingTransactionSignatureValidator(transactions.toList, prevMerkleRoot = Some(prevRoot))
      val signature =
        MerkleRootSigner.sign(Some(prevRoot), sidechainParams, transactions)(rightValidatorCrossChainPrvKey)
      val event = OutgoingTransactionsSignedEvent(rightValidatorAddress, signature.signature, signature.rootHash)
      val logEntryWithInvalidSignature =
        TransactionLogEntry(
          loggerAddress = bridgeAddress,
          logTopics = Seq(BridgeContract.OutgoingTransactionsSignedEventTopic),
          data = OutgoingTransactionsSignedEventEncoder.encode(event)
        )

      val result = validator.validate(world, header, makeExecutionResult(logEntryWithInvalidSignature)).ioValue

      result shouldBe Right(())
    }

    "outside of handover phase" in {
      val transactions =
        NonEmptyList.of(OutgoingTransaction(value = Token(10000), recipient, OutgoingTxId(0)))
      val validator = makeOutgoingTransactionSignatureValidator(transactions.toList)

      val result = validator.validate(world, header.copy(slotNumber = Slot(2)), makeExecutionResult()).ioValue

      result shouldBe Right(())
    }
  }

  def makeOutgoingTransactionSignatureValidator(
      transactions: Seq[OutgoingTransaction],
      prevMerkleRoot: Option[RootHash] = None
  ) =
    new OutgoingTransactionSignatureValidator[IO, ECDSA](
      bridgeAddress,
      sidechainParams,
      SidechainFixtures.bridgeContractStub(outgoingTransactionList = transactions, prevMerkleRoot),
      epochDerivation,
      _ => IO.pure(Right(CommitteeElectionSuccess(NonEmptyVector.of(validCandidate))))
    )

  private def makeExecutionResult(logEntries: TransactionLogEntry*): BlockExecutionResult = {
    val postWorld = TestInMemoryWorldState.worldStateGen.sample.get
    val receipts = Seq(
      Type02Receipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, Seq.empty)),
      Type02Receipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, logEntries))
    )
    BlockExecutionResult(postWorld, 0, receipts)
  }

}

object OutgoingTransactionSignatureValidatorSpec {

  val bridgeAddress: Address           = Address("0x696f686b2e6d616d626100000000000000000000")
  val sidechainParams: SidechainParams = SidechainFixtures.sidechainParams
  val world: WorldType                 = TestInMemoryWorldState.worldStateGen.sample.get
  val epochDerivation: SidechainEpochDerivation[IO] =
    SidechainFixtures.SidechainEpochDerivationStub[IO](slotsInEpoch = 12, stabilityParameter = 1)
  val handoverSlot: Slot = Slot(10)
  val header: ObftHeader = BlockGenerators.obftBlockHeaderGen.sample.get.copy(slotNumber = handoverSlot)

  val recipient: OutgoingTxRecipient =
    OutgoingTxRecipient.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840")
  val KeySet(
    rightValidatorPubKey,
    rightValidatorPrvKey,
    rightValidatorCrossChainPubKey,
    rightValidatorCrossChainPrvKey
  )                                  = CryptoGenerators.keySetGen[ECDSA].sample.get
  val rightValidatorAddress: Address = Address.fromPublicKey(rightValidatorPubKey)
  val validCandidate: ValidLeaderCandidate[ECDSA] =
    ValidLeaderCandidate[ECDSA](rightValidatorPubKey, rightValidatorCrossChainPubKey, Lovelace.ofAda(1000000))

  implicit val MerkleRootHashEncoder: SolidityAbiEncoder[merkletree.RootHash] =
    SolidityAbiEncoder[Bytes32].contramap(v => Bytes32(v.value))

  implicit def OutgoingTransactionsSignedEventEncoder[Scheme <: AbstractSignatureScheme]
      : SolidityAbiEncoder[OutgoingTransactionsSignedEvent[Scheme]] =
    SolidityAbiEncoder[(Address, Scheme#Signature, RootHash)].contramap(OutgoingTransactionsSignedEvent.unapply(_).get)

}
