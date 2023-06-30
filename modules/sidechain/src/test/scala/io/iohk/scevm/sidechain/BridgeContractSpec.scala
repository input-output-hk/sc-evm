package io.iohk.scevm.sidechain

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.EpochPhase.{ClosedTransactionBatch, Handover, Regular}
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.{ProgramResult, ProgramResultError, RevertOccurs, StorageType, WorldType}
import io.iohk.scevm.sidechain.BridgeContract._
import io.iohk.scevm.sidechain.CrossChainSignaturesService.BridgeContract.AddressWithSignature
import io.iohk.scevm.sidechain.certificate.CheckpointSigner.SignedCheckpoint
import io.iohk.scevm.sidechain.certificate.MerkleRootSigner.SignedMerkleRootHash
import io.iohk.scevm.sidechain.testing.SidechainDiffxInstances._
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.solidity.{Bytes32, SolidityAbi, SolidityAbiDecoder}
import io.iohk.scevm.testing.CryptoGenerators
import io.iohk.scevm.trustlesssidechain.cardano.Blake2bHash32
import org.scalactic.source.Position
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Assertion, EitherValues}

class BridgeContractSpec extends BridgeContractSpecBase with EitherValues with TableDrivenPropertyChecks {

  "BridgeContract.unlock" should {
    "fail when called indirectly via a contract" in {
      val incomingTransaction = IncomingTransaction(10, Address(0), IncomingTransactionId(ByteString.empty))
      val bridgePayload       = BridgeContract.buildUnlockCallPayload(incomingTransaction)
      val indirectCallPayload = SolidityAbi.solidityCall("indirectCall", bridgeAddress, bridgePayload)
      val result              = fixture.run(callContract(indirectCallAddress, indirectCallPayload)).ioValue

      shouldRevertWithMessage(result, "Only direct calls are allowed")
    }

    "succeed when using a contract as recipient but not unlock" in {
      val incomingTransaction = IncomingTransaction(10, indirectCallAddress, IncomingTransactionId(ByteString.empty))
      val bridgePayload       = BridgeContract.buildUnlockCallPayload(incomingTransaction)
      val program = for {
        initialBalance <- fixture.withWorld(_.getBalance(bridgeAddress).pure[IO])
        txResult       <- callContract0(bridgeAddress, bridgePayload)
        balanceAfter   <- fixture.withWorld(_.getBalance(bridgeAddress).pure[IO])
      } yield (txResult, initialBalance, balanceAfter)

      val (result, initialBalance, balanceAfter) = fixture.run(program).ioValue

      val log = result.logs
        .find(e => BridgeContract.isIncomingTransactionHandledEvent(bridgeAddress, e))
        .map(e => IncomingTransactionHandledEvent.decodeFromLogEntry(e))
        .getOrElse(fail("Expected log not found"))

      log.result shouldBe IncomingTransactionResult.ErrorContractRecipient
      balanceAfter shouldBe initialBalance

    }

    "logs IncomingTransactionHandledEvent" in {
      val incomingTxId        = IncomingTransactionId.fromTxHash(mainchainTxHash)
      val incomingTransaction = IncomingTransaction(10, regularAccount, incomingTxId)
      val bridgePayload       = BridgeContract.buildUnlockCallPayload(incomingTransaction)
      val result              = fixture.run(callContract0(bridgeAddress, bridgePayload)).ioValue

      val log = result.logs
        .find(e => BridgeContract.isIncomingTransactionHandledEvent(bridgeAddress, e))
        .map(e => IncomingTransactionHandledEvent.decodeFromLogEntry(e))
        .getOrElse(fail("Expected log not found"))

      assert(log.amount == UInt256(10 * ConversionRate))
      assert(log.txId.toTxHash == Right(mainchainTxHash))
      assert(log.recipient == regularAccount)
      assert(log.result == IncomingTransactionResult.Success)
    }

    "succeed when using a regular account as recipient" in {
      val incomingTransaction = IncomingTransaction(10, regularAccount, IncomingTransactionId(ByteString.empty))
      val bridgePayload       = BridgeContract.buildUnlockCallPayload(incomingTransaction)
      val result              = fixture.run(callContract(bridgeAddress, bridgePayload)).ioValue

      assert(result.isRight)
    }

    "fail when trying to unlock a transaction which was already processed" in {
      val incomingTransaction = IncomingTransaction(10, regularAccount, IncomingTransactionId(ByteString.empty))
      val bridgePayload       = BridgeContract.buildUnlockCallPayload(incomingTransaction)

      val program = for {
        _      <- callContract(bridgeAddress, bridgePayload)
        result <- callContract(bridgeAddress, bridgePayload)
      } yield result

      val result = fixture.run(program).ioValue
      shouldRevertWithMessage(result, "This transaction has already been processed")
    }

    "return empty byteString about last processed transaction when no transactions were processed" in {
      val result = fixture
        .run(
          callContract(
            bridgeAddress,
            SolidityAbi.solidityCall(BridgeContract.Methods.GetLastProcessedTransaction)
          )
        )
        .ioValue

      SolidityAbiDecoder[Tuple1[ByteString]].decode(result.value)._1 shouldBe ByteString.empty
    }

    "set unlocked transaction as the last processed transaction" in {
      val incomingTransaction1 =
        IncomingTransaction(10, regularAccount, IncomingTransactionId(ByteString(Hex.decodeAsArrayUnsafe("dead1"))))

      val program = for {
        _  <- callContract(bridgeAddress, BridgeContract.buildUnlockCallPayload(incomingTransaction1))
        r3 <- getLastProcessedTx
      } yield r3

      val result = fixture.run(program).ioValue
      result shouldBe Some(incomingTransaction1.txId)
    }

    "set unlocked transaction as the last processed transaction - multiple transactions" in {
      val incomingTransaction1 =
        IncomingTransaction(10, regularAccount, IncomingTransactionId(ByteString(Hex.decodeAsArrayUnsafe("dead1"))))
      val incomingTransaction2 =
        IncomingTransaction(10, regularAccount, IncomingTransactionId(ByteString(Hex.decodeAsArrayUnsafe("dead2"))))

      val program = for {
        _  <- callContract(bridgeAddress, BridgeContract.buildUnlockCallPayload(incomingTransaction1))
        _  <- callContract(bridgeAddress, BridgeContract.buildUnlockCallPayload(incomingTransaction2))
        r3 <- getLastProcessedTx
      } yield r3

      val result = fixture.run(program).ioValue
      result shouldBe Some(incomingTransaction2.txId)
    }
  }

  "BridgeContract.lock" should {
    "fail when called with a value lower than the minimum" in {
      val bridgePayload = SolidityAbi.solidityCall(
        BridgeContract.Methods.Lock,
        Hex.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840")
      )
      val result = fixture.run(callContract(bridgeAddress, bridgePayload, regularAccount, 0)).ioValue

      shouldRevertWithMessage(result, "Bridge: amount should be strictly positive")
    }

    "add transaction to list of outgoing transactions" in {
      val bridgePayload = SolidityAbi.solidityCall(
        BridgeContract.Methods.Lock,
        Hex.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840")
      )

      val program = for {
        resultLock <- callContract(bridgeAddress, bridgePayload, regularAccount, 200 * ConversionRate)
        _          <- StateT.liftF(IO.fromEither(resultLock.left.map(pe => new RuntimeException(pe.error.toString))))
        resultList <- getOutgoingTx(UInt256(0))
      } yield resultList

      val resultList = fixture.run(program).ioValue
      resultList shouldBe Seq(
        OutgoingTransaction(
          Token(200),
          OutgoingTxRecipient.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"),
          OutgoingTxId(0)
        )
      )
    }
  }
  "BridgeContract.sign" should {
    "return true" in {

      val program = for {
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   SidechainEpoch(0),
                   Handover
                 )
               )
             )
        result <- callContract(
                    bridgeAddress,
                    BridgeContract
                      .buildSignCallPayload(
                        ECDSASignature.fromHexUnsafe(
                          "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e1b"
                        )
                      )
                  )
      } yield result

      val rawResult = fixture.run(program).ioValue
      SolidityAbiDecoder[Tuple1[Boolean]].decode(rawResult.value)._1 shouldBe true
    }

    "log HandoverSignedEvent" in {
      val prvKey           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val signature        = prvKey.sign(Blake2bHash32.hash(ByteString("scevm".getBytes)).bytes)
      val sidechainEpoch   = SidechainEpoch(1123)
      val validatorAddress = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   sidechainEpoch,
                   Handover
                 )
               )
             )
        result <- fixture.callContract0(
                    bridgeAddress,
                    BridgeContract.buildSignCallPayload(signature),
                    sender = validatorAddress
                  )
      } yield result
      val result = fixture.run(program).ioValue

      SolidityAbiDecoder[Tuple1[Boolean]].decode(result.toEither.value)._1 shouldBe true
      expectHandoverLog(result, validatorAddress, signature, sidechainEpoch)
    }

    "getSignatures should return no signatures if none were submitted" in {
      val prvKey           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val sidechainEpoch   = SidechainEpoch(1123)
      val validatorAddress = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val result           = fixture.run(getHandoverSignatures(sidechainEpoch, List(validatorAddress)))
      result.ioValue shouldBe List.empty
    }

    "return submitted signatures" in {
      val prvKey           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val signature        = prvKey.sign(Blake2bHash32.hash(ByteString("scevm".getBytes)).bytes)
      val validatorAddress = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))

      val prvKey2           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val signature2        = prvKey.sign(Blake2bHash32.hash(ByteString("scevm".getBytes)).bytes)
      val validatorAddress2 = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey2))

      val sidechainEpoch = SidechainEpoch(1123)
      val signPayload    = BridgeContract.buildSignCallPayload(signature)
      val signPayload2   = BridgeContract.buildSignCallPayload(signature2)

      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   sidechainEpoch,
                   Handover
                 )
               )
             )
        resultSign <- callContract(
                        bridgeAddress,
                        signPayload,
                        sender = validatorAddress
                      )
        _ <- StateT.liftF(IO.fromEither(resultSign.left.map(pe => new RuntimeException(pe.show))))
        _ <- fixture.setCoinbaseAccount(validatorAddress2)
        resultSign2 <- callContract(
                         bridgeAddress,
                         signPayload2,
                         sender = validatorAddress2
                       )
        _                   <- StateT.liftF(IO.fromEither(resultSign2.left.map(pe => new RuntimeException(pe.show))))
        resultGetSignatures <- getHandoverSignatures(sidechainEpoch, List(validatorAddress, validatorAddress2))
      } yield resultGetSignatures

      val result = fixture.run(program).ioValue

      result shouldBe List(
        AddressWithSignature(validatorAddress, signature),
        AddressWithSignature(validatorAddress2, signature2)
      )
    }
  }

  "BridgeContract.buildSignHandoverAndTxCallPayload" should {
    "return true" in {

      val program = for {
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   SidechainEpoch(0),
                   Handover
                 )
               )
             )
        result <- callContract(
                    bridgeAddress,
                    BridgeContract
                      .buildSignCallPayload(
                        ECDSASignature.fromHexUnsafe(
                          "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e1b"
                        ),
                        txsBatchMRH = Some(
                          SignedMerkleRootHash(
                            RootHash(ByteString("2" * 32)),
                            ECDSASignature.fromHexUnsafe(
                              "f8ec6c7f935d387aaa1693b3bf338cbb8f53013da8a5a234f9c488bacac01af259297e69aee0df27f553c0a1164df827d016125c16af93c99be2c19f36d2f66e1b"
                            )
                          )
                        )
                      )
                  )
      } yield result

      val rawResult = fixture.run(program).ioValue
      SolidityAbiDecoder[Tuple1[Boolean]].decode(rawResult.value)._1 shouldBe true
    }

    "log HandoverSignedEvent" in {
      val prvKey               = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val handoverSignature    = prvKey.sign(Blake2bHash32.hash(ByteString("scevm".getBytes)).bytes)
      val transactionSignature = prvKey.sign(Blake2bHash32.hash(ByteString("txs".getBytes)).bytes)
      val sidechainEpoch       = SidechainEpoch(1123)
      val validatorAddress     = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val rootHash             = RootHash(ByteString("b" * 32))
      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   sidechainEpoch,
                   Handover
                 )
               )
             )
        result <- fixture.callContract0(
                    bridgeAddress,
                    BridgeContract
                      .buildSignCallPayload(
                        handoverSignature,
                        txsBatchMRH = Some(SignedMerkleRootHash(rootHash, transactionSignature))
                      ),
                    sender = validatorAddress
                  )
      } yield result
      val result = fixture.run(program).ioValue

      SolidityAbiDecoder[Tuple1[Boolean]].decode(result.toEither.value)._1 shouldBe true
      expectHandoverLog(result, validatorAddress, handoverSignature, sidechainEpoch)

      val outgoingTxLog = result.logs
        .find(e => BridgeContract.isOutgoingTransactionsSigned(bridgeAddress, e))
        .map(e => OutgoingTransactionsSignedEventDecoder[ECDSA].decode(e.data))
        .getOrElse(fail("Expected outgoing transactions signed event not found in logs"))

      assert(
        outgoingTxLog == OutgoingTransactionsSignedEvent[ECDSA](
          validator = validatorAddress,
          signature = transactionSignature,
          merkleRootHash = rootHash
        ),
        outgoingTxLog
      )
    }

    "log CheckpointBlockSignedEvent" in {
      val prvKey              = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val handoverSignature   = prvKey.sign(Blake2bHash32.hash(ByteString("scevm".getBytes)).bytes)
      val checkpointSignature = prvKey.sign(Blake2bHash32.hash(ByteString("checkpoint".getBytes)).bytes)
      val sidechainEpoch      = SidechainEpoch(1123)
      val validatorAddress    = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val hash                = BlockHash(ByteString("b" * 32))
      val blockNumber         = BlockNumber(42)
      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   sidechainEpoch,
                   Handover
                 )
               )
             )
        result <- fixture.callContract0(
                    bridgeAddress,
                    BridgeContract
                      .buildSignCallPayload(
                        handoverSignature,
                        checkpoint = Some(SignedCheckpoint(hash, blockNumber, checkpointSignature))
                      ),
                    sender = validatorAddress
                  )
      } yield result
      val result = fixture.run(program).ioValue

      SolidityAbiDecoder[Tuple1[Boolean]].decode(result.toEither.value)._1 shouldBe true
      expectHandoverLog(result, validatorAddress, handoverSignature, sidechainEpoch)

      val checkpointLog = result.logs
        .find(e => BridgeContract.isCheckpointSignedEvent(bridgeAddress, e))
        .map(e => CheckpointBlockSignedEventDecoder.decode(e.data))
        .getOrElse(fail("Expected checkpoint signed event not found in logs"))

      assert(
        checkpointLog == CheckpointBlockSignedEvent(
          validator = validatorAddress,
          signature = checkpointSignature,
          block = CheckpointBlock(Bytes32(hash.byteString), blockNumber.toUInt256)
        ),
        checkpointLog
      )
    }

    "cause 'getSignatures' return submitted signatures" in {
      val prvKey           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val handoverSig      = prvKey.sign(Blake2bHash32.hash(ByteString("handover".getBytes)).bytes)
      val txSig            = prvKey.sign(Blake2bHash32.hash(ByteString("txs".getBytes)).bytes)
      val validatorAddress = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))

      val prvKey2           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val handoverSig2      = prvKey2.sign(Blake2bHash32.hash(ByteString("handover".getBytes)).bytes)
      val txSig2            = prvKey2.sign(Blake2bHash32.hash(ByteString("txs".getBytes)).bytes)
      val validatorAddress2 = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey2))

      val sidechainEpoch     = SidechainEpoch(1123)
      val irrelevantRootHash = RootHash(ByteString("x" * 32))
      val signPayload = BridgeContract.buildSignCallPayload(
        handoverSig,
        txsBatchMRH = Some(SignedMerkleRootHash(irrelevantRootHash, txSig))
      )
      val signPayload2 = BridgeContract.buildSignCallPayload(
        handoverSig2,
        txsBatchMRH = Some(SignedMerkleRootHash(irrelevantRootHash, txSig2))
      )

      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   sidechainEpoch,
                   Handover
                 )
               )
             )
        resultSign <- callContract(
                        bridgeAddress,
                        signPayload,
                        sender = validatorAddress
                      )
        _ <- StateT.liftF(IO.fromEither(resultSign.left.map(pe => new RuntimeException(pe.show))))
        _ <- fixture.setCoinbaseAccount(validatorAddress2)
        resultSign2 <- callContract(
                         bridgeAddress,
                         signPayload2,
                         sender = validatorAddress2
                       )
        _                           <- StateT.liftF(IO.fromEither(resultSign2.left.map(pe => new RuntimeException(pe.show))))
        resultGetHandoverSignatures <- getHandoverSignatures(sidechainEpoch, List(validatorAddress, validatorAddress2))
        resultGetOutgoingTxSignatures <-
          getOutogingTransactionSignatures(sidechainEpoch, List(validatorAddress, validatorAddress2))
      } yield (resultGetHandoverSignatures, resultGetOutgoingTxSignatures)

      val (hanvoverSignatures, outoingTransactionSignatures) = fixture.run(program).ioValue

      hanvoverSignatures shouldBe List(
        AddressWithSignature(validatorAddress, handoverSig),
        AddressWithSignature(validatorAddress2, handoverSig2)
      )
      outoingTransactionSignatures shouldBe List(
        AddressWithSignature(validatorAddress, txSig),
        AddressWithSignature(validatorAddress2, txSig2)
      )
    }

    "allow to fetch checkpoints" in {
      val prvKey              = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val handoverSignature   = prvKey.sign(Blake2bHash32.hash(ByteString("scevm".getBytes)).bytes)
      val checkpointSignature = prvKey.sign(Blake2bHash32.hash(ByteString("checkpoint".getBytes)).bytes)
      val sidechainEpoch      = SidechainEpoch(1123)
      val validatorAddress    = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val hash                = BlockHash(ByteString("b" * 32))
      val blockNumber         = BlockNumber(42)
      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <- fixture.updateWorld(state =>
               IO(
                 BridgeContract.prepareState(bridgeAddress)(
                   state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                   sidechainEpoch,
                   Handover
                 )
               )
             )
        _ <- fixture.callContract0(
               bridgeAddress,
               BridgeContract
                 .buildSignCallPayload(
                   handoverSignature,
                   checkpoint = Some(SignedCheckpoint(hash, blockNumber, checkpointSignature))
                 ),
               sender = validatorAddress
             )
        checkpointBlock <- fixture.withWorld(bridgeContract.getCheckpointBlock(sidechainEpoch).run)
      } yield checkpointBlock
      val result = fixture.run(program).ioValue

      assert(result == Some(CheckpointBlock(Bytes32(hash.byteString), blockNumber.toUInt256)))
    }

    "previous merkle root hash is available after signing second different txs batch" in {
      val prvKey           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val validatorAddress = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val handoverSig      = prvKey.sign(Blake2bHash32.hash(ByteString("handover".getBytes)).bytes)
      val txSig            = prvKey.sign(Blake2bHash32.hash(ByteString("txs".getBytes)).bytes)
      val txsMRH1          = RootHash(ByteString("1" * 32))
      val txsMRH2          = RootHash(ByteString("2" * 32))

      def payload(h: RootHash) =
        BridgeContract.buildSignCallPayload(handoverSig, txsBatchMRH = Some(SignedMerkleRootHash(h, txSig)))

      val program = for {
        _ <- fixture.setCoinbaseAccount(validatorAddress)
        _ <-
          fixture.updateWorld(state =>
            IO(
              BridgeContract.prepareState(bridgeAddress)(
                state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                SidechainEpoch(1123),
                Handover
              )
            )
          )
        // before first signing, there is no current hash in contract internal state
        s0 <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
        _  <- callContract(bridgeAddress, payload(txsMRH1), validatorAddress)
        // after first signing, there is current hash but no previous hash in contract internal state
        s1a <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
        _   <- callContract(bridgeAddress, payload(txsMRH1), validatorAddress)
        // no change because signing was called called subsequently with same hash
        s1b <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
        // change of epoch makes txsMRH1 the hash to return
        _ <-
          fixture.updateWorld(state =>
            IO(
              BridgeContract.prepareState(bridgeAddress)(
                state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                SidechainEpoch(1124),
                Handover
              )
            )
          )
        s2a <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
        // signing with txsMRH2 will make it the returned hash in the next epochs
        _   <- callContract(bridgeAddress, payload(txsMRH2), validatorAddress)
        s2b <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
        // again no change because signing was called subsequently with same hash
        _   <- callContract(bridgeAddress, payload(txsMRH2), validatorAddress)
        s2c <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
        // again no change because signing was called subsequently with same hash
        _ <-
          fixture.updateWorld(state =>
            IO(
              BridgeContract.prepareState(bridgeAddress)(
                state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
                SidechainEpoch(1125),
                Handover
              )
            )
          )
        s3 <- fixture.withWorld(bridgeContract.getPreviousMerkleRoot.run)
      } yield (s0, s1a, s1b, s2a, s2b, s2c, s3)

      val result = fixture.run(program).ioValue
      result shouldBe (None, None, None, Some(txsMRH1), Some(txsMRH1), Some(txsMRH1), Some(txsMRH2))
    }

    "it should be possible to fetch merkle root for any given epoch as soon as it is available" in {
      val prvKey           = CryptoGenerators.ecdsaPrivateKeyGen.sample.get
      val validatorAddress = Address.fromPublicKey(ECDSA.PublicKey.fromPrivateKey(prvKey))
      val handoverSig      = prvKey.sign(Blake2bHash32.hash(ByteString("handover".getBytes)).bytes)
      val txSig            = prvKey.sign(Blake2bHash32.hash(ByteString("txs".getBytes)).bytes)
      val txsMRH1          = RootHash(ByteString("1" * 32))
      val txsMRH2          = RootHash(ByteString("2" * 32))

      def payload(h: RootHash) =
        BridgeContract.buildSignCallPayload(handoverSig, txsBatchMRH = Some(SignedMerkleRootHash(h, txSig)))

      def getMerkleRootForEpoch(epoch: SidechainEpoch) = fixture.withWorld(
        bridgeContract.getTransactionsMerkleRootHash(epoch).run
      )

      def submitMerkleRoot(rootHash: RootHash) =
        callContract(bridgeAddress, payload(rootHash), validatorAddress)

      def updateContractState(epoch: SidechainEpoch) =
        fixture.updateWorld(state =>
          IO(
            BridgeContract.prepareState(bridgeAddress)(
              state.modifyBlockContext(_.copy(slotNumber = Slot(100))),
              epoch,
              Handover
            )
          )
        )

      val program = for {
        _   <- fixture.setCoinbaseAccount(validatorAddress)
        s1b <- getMerkleRootForEpoch(SidechainEpoch(1123))
        s2b <- getMerkleRootForEpoch(SidechainEpoch(1124))
        s3b <- getMerkleRootForEpoch(SidechainEpoch(1125))

        _ <- updateContractState(SidechainEpoch(1123))

        _ <- submitMerkleRoot(txsMRH1)
        _ <- submitMerkleRoot(txsMRH1)

        _ <- updateContractState(SidechainEpoch(1124))

        _ <- submitMerkleRoot(txsMRH2)
        _ <- submitMerkleRoot(txsMRH2)

        _   <- updateContractState(SidechainEpoch(1125))
        s1a <- getMerkleRootForEpoch(SidechainEpoch(1123))
        s2a <- getMerkleRootForEpoch(SidechainEpoch(1124))
        s3a <- getMerkleRootForEpoch(SidechainEpoch(1125))
      } yield (s1b, s2b, s3b, s1a, s2a, s3a)

      val result = fixture.run(program).ioValue
      result shouldMatchTo (None, None, None, Some(txsMRH1), Some(txsMRH2), None)
    }
  }

  "prepareState correctly injects values" in {
    forAll(Table("phase", Regular, ClosedTransactionBatch, Handover)) { expectedPhase =>
      val program = for {
        _ <-
          fixture.updateWorld(state =>
            IO(
              BridgeContract.prepareState(bridgeAddress)(
                state.modifyBlockContext(_.copy(slotNumber = Slot(123))),
                SidechainEpoch(42),
                expectedPhase
              )
            )
          )
        currentEpoch <- fixture.withWorld(bridgeContract.currentEpoch.run)
        currentSlot  <- fixture.withWorld(bridgeContract.currentSlot.run)
        phase        <- fixture.withWorld(bridgeContract.epochPhase.run)
      } yield (currentEpoch, currentSlot, phase)

      val (currentEpoch, currentSlot, actualPhase) = fixture.run(program).ioValue

      currentEpoch shouldBe SidechainEpoch(42)
      currentSlot shouldBe Slot(123)
      actualPhase shouldBe expectedPhase
    }
  }

  private def getHandoverSignatures(sidechainEpoch: SidechainEpoch, validators: List[Address]) =
    StateT.inspectF[IO, WorldType, List[AddressWithSignature[ECDSASignature]]](
      bridgeContract.getHandoverSignatures(sidechainEpoch, validators).run
    )

  private def getOutogingTransactionSignatures(sidechainEpoch: SidechainEpoch, validators: List[Address]) =
    StateT.inspectF[IO, WorldType, List[AddressWithSignature[ECDSASignature]]](
      bridgeContract.getOutgoingTransactionSignatures(sidechainEpoch, validators).run
    )

  private def getOutgoingTx(batch: UInt256) =
    StateT.inspectF[IO, WorldType, Seq[OutgoingTransaction]](
      bridgeContract.getTransactionsInBatch(batch).run
    )

  private def getLastProcessedTx =
    StateT.inspectF[IO, WorldType, Option[IncomingTransactionId]](world =>
      bridgeContract.getLastProcessedIncomingTransaction(world)
    )

  def shouldRevertWithMessage(
      result: Either[ProgramResultError, ByteString],
      message: String
  )(implicit pos: Position): Assertion = result match {
    case Left(ProgramResultError(error, data)) =>
      error shouldBe RevertOccurs
      // Error message is encoded as a call to `Error(<errorMessageString>)` so we drop the function selector (4 bytes)
      val errorMessage = SolidityAbiDecoder[Tuple1[String]].decode(data.drop(4))._1
      errorMessage shouldBe message
    case Right(value) =>
      fail(s"Expected to return error, but returned ${Hex.toHexString(value.toArray)}")
  }

  def callContract0(
      address: Address,
      data: ByteString,
      sender: Address = Address(0),
      value: UInt256 = 0
  ): StateT[IO, WorldType, ProgramResult[WorldType, StorageType]] =
    fixture.callContract0(address, data, sender, value)

  def callContract(
      address: Address,
      data: ByteString,
      sender: Address = Address(0),
      value: UInt256 = 0
  ): StateT[IO, WorldType, Either[ProgramResultError, ByteString]] =
    fixture.callContract(address, data, sender, value)

  private def expectHandoverLog(
      result: ProgramResult[WorldType, StorageType],
      validatorAddress: Address,
      handoverSignature: ECDSASignature,
      sidechainEpoch: SidechainEpoch
  ) = {
    val handoverLog = result.logs
      .find(e => BridgeContract.isHandoverSignedEvent(bridgeAddress, e))
      .map(e => HandoverSignedEventDecoder[ECDSA].decode(e.data))
      .getOrElse(fail("Expected handover signed event not found in logs"))

    assert(
      handoverLog == HandoverSignedEvent[ECDSA](
        validator = validatorAddress,
        signature = handoverSignature,
        epoch = sidechainEpoch
      ),
      handoverLog
    )
  }
}
