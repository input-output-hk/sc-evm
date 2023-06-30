package io.iohk.scevm.sidechain.consensus

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.{
  Address,
  ObftHeader,
  ReceiptBody,
  Slot,
  TransactionLogEntry,
  TransactionOutcome,
  Type02Receipt
}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.BridgeContract.{EcdsaSignatureDecoder, HandoverSignedEvent}
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.testing.SidechainGenerators.{rootHashGen, sidechainParamsGen}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.{BridgeContract, SidechainEpoch, SidechainEpochDerivation, SidechainFixtures}
import io.iohk.scevm.testing.CryptoGenerators._
import io.iohk.scevm.testing.Generators.{addressGen, longGen}
import io.iohk.scevm.testing.{BlockGenerators, CryptoGenerators, IOSupport, NormalPatience}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class HandoverSignatureValidatorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with EitherValues
    with IOSupport
    with NormalPatience {

  val epochGen: Gen[SidechainEpoch] = longGen(0L, 10000000L).map(SidechainEpoch(_))
  val keysGen: Gen[NonEmptyList[CryptoGenerators.KeySet[ECDSA]]] = for {
    keySet       <- keySetGen[ECDSA]
    restKeyPairs <- Gen.listOf(keySetGen[ECDSA])
  } yield NonEmptyList.of(keySet, restKeyPairs: _*)

  val HandoverSignedEventTopics: Seq[ByteString] = Seq(BridgeContract.HandoverSignedEventTopic)

  val otherTopic: ByteString  = ByteString("something_else")
  val handoverSlot: Slot      = Slot(10)
  val nonHandoverSlot: Slot   = Slot(2)
  val header: ObftHeader      = BlockGenerators.obftBlockHeaderGen.sample.get.copy(slotNumber = handoverSlot)
  val initialWorld: WorldType = TestInMemoryWorldState.worldStateGen.sample.get
  val epochDerivation: SidechainEpochDerivation[IO] =
    SidechainFixtures.SidechainEpochDerivationStub[IO](slotsInEpoch = 12, stabilityParameter = 1)

  private def toElectionResult[Scheme <: AbstractSignatureScheme](
      keyPairs: NonEmptyList[KeySet[Scheme]]
  ): CommitteeElectionSuccess[Scheme] =
    CommitteeElectionSuccess[Scheme](
      keyPairs.map(keys => ValidLeaderCandidate[Scheme](keys.pubKey, keys.crossChainPubKey, Lovelace(0L))).toNev
    )

  private def mkGetCommittee(
      epoch: SidechainEpoch,
      keysCurrent: NonEmptyList[KeySet[ECDSA]],
      keysNext: NonEmptyList[KeySet[ECDSA]]
  ): SidechainEpoch => IO[Either[ElectionFailure, CommitteeElectionSuccess[ECDSA]]] =
    (e: SidechainEpoch) =>
      if (e == epoch) IO(Right(toElectionResult(keysCurrent)))
      else if (e == epoch.next) IO(Right(toElectionResult[ECDSA](keysNext)))
      else IO.raiseError(new Exception(s"Unexpected invocation for epoch $e"))

  private def makeExecutionResult(logEntries: TransactionLogEntry*): BlockExecutionResult = {
    val postWorld = TestInMemoryWorldState.worldStateGen.sample.get
    val receipts = Seq(
      Type02Receipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, Seq.empty)),
      Type02Receipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, logEntries))
    )
    BlockExecutionResult(postWorld, 0, receipts)
  }

  private def makeValidEventLogEntry(
      bridgeAddress: Address,
      sidechainParams: SidechainParams,
      epoch: SidechainEpoch,
      keysCurrent: NonEmptyList[KeySet[ECDSA]],
      keysNext: NonEmptyList[KeySet[ECDSA]],
      prevRootHash: Option[RootHash]
  ) = {
    val validatorKeys    = keysCurrent.head
    val validatorAddress = Address.fromPublicKey(validatorKeys.pubKey)
    val nextCommittee    = keysNext.map(_.pubKey)
    val message =
      new CommitteeHandoverSigner[IO].messageHash(sidechainParams, nextCommittee, prevRootHash, epoch).ioValue
    val signature  = validatorKeys.crossChainPrvKey.sign(message.bytes)
    val event      = HandoverSignedEvent[ECDSA](validatorAddress, signature, epoch)
    val eventBytes = BridgeContract.HandoverSignedEventEncoder[ECDSA].encode(event)
    TransactionLogEntry(bridgeAddress, HandoverSignedEventTopics, eventBytes)
  }

  "invalidates block" when {

    "signature is invalid" in {

      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, Gen.option(rootHashGen)) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash) =>
          val getCommittee   = mkGetCommittee(epoch, keysCurrent, keysNext)
          val bridgeContract = SidechainFixtures.bridgeContractStub(prevMerkleRoot = prevRootHash)
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val validatorKeys    = keysCurrent.head
          val validatorAddress = Address.fromPublicKey(validatorKeys.pubKey)
          val invalidSignature = ECDSASignature.fromBytesUnsafe(ByteString(Array.fill(65)(0.toByte)))
          val event            = HandoverSignedEvent[ECDSA](validatorAddress, invalidSignature, epoch)
          val eventBytes       = BridgeContract.HandoverSignedEventEncoder[ECDSA].encode(event)
          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              makeExecutionResult(TransactionLogEntry(bridgeAddress, HandoverSignedEventTopics, eventBytes))
            )
            .ioValue
          result.left.value.message should startWith("Invalid signature in event")
      }
    }

    "there is no public key matching event/block validator address in committee" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, keySetGen[ECDSA]) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, otherKeyPair) =>
          whenever(keysCurrent.forall(_.pubKey != otherKeyPair.pubKey)) {
            val getCommittee   = mkGetCommittee(epoch, keysCurrent, keysNext)
            val bridgeContract = SidechainFixtures.bridgeContractStub()
            val handoverSignatureValidator =
              new HandoverSignatureValidator[IO, ECDSA](
                bridgeAddress,
                sidechainParams,
                bridgeContract,
                epochDerivation,
                getCommittee,
                new CommitteeHandoverSigner[IO]
              )

            val validatorKeys    = otherKeyPair
            val validatorAddress = Address.fromPublicKey(validatorKeys.pubKey)
            val invalidSignature = ECDSASignature.fromBytesUnsafe(ByteString(Array.fill(65)(0.toByte)))
            val event            = HandoverSignedEvent[ECDSA](validatorAddress, invalidSignature, epoch)
            val eventBytes       = BridgeContract.HandoverSignedEventEncoder[ECDSA].encode(event)
            val result = handoverSignatureValidator
              .validate(
                initialWorld,
                header,
                makeExecutionResult(TransactionLogEntry(bridgeAddress, HandoverSignedEventTopics, eventBytes))
              )
              .ioValue
            result.left.value.message should include(
              "The address contained in the event does not belong to a validator in the current committee"
            )
          }
      }
    }

    "is unable to get committee for event epoch" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext) =>
          val getCommittee = (e: SidechainEpoch) =>
            if (e == epoch) IO(Left(ElectionFailure.dataNotAvailable(0, 1)))
            else if (e == epoch.next) IO(Right(toElectionResult[ECDSA](keysNext)))
            else IO.raiseError(new Exception(s"Unexpected invocation for epoch $e"))
          val bridgeContract = SidechainFixtures.bridgeContractStub()
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              makeExecutionResult(
                makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, None)
              )
            )
            .ioValue
          result.left.value.message should include(
            "Currently no data is available on the main chain for this epoch (mainchain = 0, sidechain = 1)"
          )
          result.left.value.message should include(s"$epoch")
      }
    }

    "is unable to get committee for the next epoch" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext) =>
          val getCommittee = (e: SidechainEpoch) =>
            if (e == epoch) IO(Right(toElectionResult[ECDSA](keysCurrent)))
            else if (e == epoch.next) IO(Left(ElectionFailure.notEnoughCandidates))
            else IO.raiseError(new Exception(s"Unexpected invocation for epoch $e"))

          val bridgeContract = SidechainFixtures.bridgeContractStub()
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              makeExecutionResult(
                makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, None)
              )
            )
            .ioValue
          result.left.value.message should include("Not enough candidates")
          result.left.value.message should include(s"${epoch.next}")
      }
    }

    "there is no handover signature event inside of the handover phase" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, Gen.option(rootHashGen)) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash) =>
          val getCommittee   = mkGetCommittee(epoch, keysCurrent, keysNext)
          val bridgeContract = SidechainFixtures.bridgeContractStub(prevMerkleRoot = prevRootHash)
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              makeExecutionResult()
            )
            .ioValue
          result.left.value.message should include("Validator should have signed the handover")
      }
    }

    "there is more than one handover event inside of the handover phase" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, Gen.option(rootHashGen)) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash) =>
          val getCommittee   = mkGetCommittee(epoch, keysCurrent, keysNext)
          val bridgeContract = SidechainFixtures.bridgeContractStub(prevMerkleRoot = prevRootHash)
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              makeExecutionResult(
                makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash),
                makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash)
              )
            )
            .ioValue
          result.left.value.message should include("More than one handover signature event was emitted")
      }
    }

    "the signature is not using the last merkle root" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext) =>
          val getCommittee = mkGetCommittee(epoch, keysCurrent, keysNext)

          val prevRootHash = RootHash(Hex.decodeUnsafe("01" * 32))
          val newRootHash  = RootHash(Hex.decodeUnsafe("02" * 32))

          val executionResult = makeExecutionResult(
            makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, Some(prevRootHash))
          )
          val bridgeContract =
            SidechainFixtures.bridgeContractStub(
              prevMerkleRoot = Some(prevRootHash),
              stateRootHashToMerkleRoot = _ => Some(newRootHash)
            )
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              executionResult
            )
            .ioValue
          result.left.value.message should include("Invalid signature in event")
      }
    }
  }

  "validates block" when {
    "there is no handover signature event outside of the handover phase" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, Gen.option(rootHashGen)) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash) =>
          val getCommittee   = mkGetCommittee(epoch, keysCurrent, keysNext)
          val bridgeContract = SidechainFixtures.bridgeContractStub(prevMerkleRoot = prevRootHash)
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header.copy(slotNumber = nonHandoverSlot),
              makeExecutionResult()
            )
            .ioValue
          result shouldBe Right(())
      }
    }

    "signature event message is expected and signature matches" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, Gen.option(rootHashGen)) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash) =>
          val getCommittee   = mkGetCommittee(epoch, keysCurrent, keysNext)
          val bridgeContract = SidechainFixtures.bridgeContractStub(prevMerkleRoot = prevRootHash)
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )

          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              makeExecutionResult(
                makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash)
              )
            )
            .ioValue
          result shouldBe Right(())
      }
    }

    "signature event message is expected and there is a new root hash" in {
      forAll(addressGen, sidechainParamsGen, epochGen, keysGen, keysGen, Gen.option(rootHashGen)) {
        case (bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, prevRootHash) =>
          val newRootHash  = rootHashGen.sample.get
          val getCommittee = mkGetCommittee(epoch, keysCurrent, keysNext)
          val execResult = makeExecutionResult(
            makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keysCurrent, keysNext, Some(newRootHash))
          )
          val bridgeContract = SidechainFixtures.bridgeContractStub(
            prevMerkleRoot = prevRootHash,
            stateRootHashToMerkleRoot = Map(execResult.worldState.stateRootHash -> newRootHash).lift
          )
          val handoverSignatureValidator =
            new HandoverSignatureValidator[IO, ECDSA](
              bridgeAddress,
              sidechainParams,
              bridgeContract,
              epochDerivation,
              getCommittee,
              new CommitteeHandoverSigner[IO]
            )
          val result = handoverSignatureValidator
            .validate(
              initialWorld,
              header,
              execResult
            )
            .ioValue
          result shouldBe Right(())
      }
    }

  }

  "short circuits on failed entry" in {
    forAll(addressGen, sidechainParamsGen, epochGen, keysGen, Gen.option(rootHashGen)) {
      case (bridgeAddress, sidechainParams, epoch, keys, prevRootHash) =>
        def countingGetCommittee(
            counter: Ref[IO, Int]
        ): SidechainEpoch => IO[Either[ElectionFailure, CommitteeElectionSuccess[ECDSA]]] =
          (_: SidechainEpoch) => counter.update(_ + 1) >> IO(Right(toElectionResult(keys)))

        val validatorKeys    = keys.head
        val validatorAddress = Address.fromPublicKey(validatorKeys.pubKey)
        val invalidSignature = ECDSASignature.fromBytesUnsafe(ByteString(Array.fill(65)(0.toByte)))
        val event            = HandoverSignedEvent[ECDSA](validatorAddress, invalidSignature, epoch)
        val eventBytes       = BridgeContract.HandoverSignedEventEncoder[ECDSA].encode(event)
        val invalidLogEntry  = TransactionLogEntry(bridgeAddress, HandoverSignedEventTopics, eventBytes)

        (for {
          counter       <- IO.ref(0)
          getCommittee   = countingGetCommittee(counter)
          bridgeContract = SidechainFixtures.bridgeContractStub(prevMerkleRoot = prevRootHash)
          validator = new HandoverSignatureValidator[IO, ECDSA](
                        bridgeAddress,
                        sidechainParams,
                        bridgeContract,
                        epochDerivation,
                        getCommittee,
                        new CommitteeHandoverSigner[IO]
                      )
          result <- validator.validate(
                      initialWorld,
                      header,
                      makeExecutionResult(
                        invalidLogEntry,
                        makeValidEventLogEntry(bridgeAddress, sidechainParams, epoch, keys, keys, prevRootHash)
                      )
                    )
          count <- counter.get
        } yield {
          result.isLeft shouldBe true
          //Validation of first entry can't decode epoch, validation of second should not run
          count shouldBe <=(2)
        }).ioValue
    }
  }

  "crashes when event can't be decoded" in {
    forAll(addressGen, sidechainParamsGen, epochGen, keysGen) { case (bridgeAddress, sidechainParams, epoch, keys) =>
      val bridgeContract = SidechainFixtures.bridgeContractStub()
      val validator =
        new HandoverSignatureValidator[IO, ECDSA](
          bridgeAddress,
          sidechainParams,
          bridgeContract,
          epochDerivation,
          mkGetCommittee(epoch, keys, keys),
          new CommitteeHandoverSigner[IO]
        )

      val invalidLogEntry = TransactionLogEntry(bridgeAddress, HandoverSignedEventTopics, ByteString.empty)
      val result          = validator.validate(initialWorld, header, makeExecutionResult(invalidLogEntry)).attempt.ioValue

      result.isLeft shouldBe true
    }
  }
}
