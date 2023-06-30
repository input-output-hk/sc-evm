package io.iohk.scevm.rpc.sidechain.controller

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import cats.{Applicative, Show}
import com.softwaremill.diffx.generic.auto._
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import com.softwaremill.diffx.{ObjectMatcher, SeqMatcher}
import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource
import io.iohk.scevm.cardanofollower.datasource.{
  EpochData,
  LeaderCandidate,
  MainchainDataSource,
  PendingDeregistration,
  PendingRegistration,
  PendingRegistrationChange,
  RegistrationData
}
import io.iohk.scevm.domain
import io.iohk.scevm.domain.{BlockHash, BlockNumber, EpochPhase, MainchainPublicKey, Slot}
import io.iohk.scevm.ledger.LeaderElection.{DataNotAvailable, ElectionFailure}
import io.iohk.scevm.plutus.DatumEncoder
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.rpc.sidechain.controller.SidechainControllerHelper._
import io.iohk.scevm.rpc.sidechain.{SidechainController, SidechainEpochParam}
import io.iohk.scevm.sidechain.CrossChainSignaturesService.CrossChainSignatures
import io.iohk.scevm.sidechain.MainchainStatusProvider.MainchainStatus
import io.iohk.scevm.sidechain.PendingTransactionsService.PendingTransactionsResponse
import io.iohk.scevm.sidechain.SidechainFixtures.{MainchainEpochDerivationStub, SidechainEpochDerivationStub}
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.transactions.MerkleProofService.{
  MerkleProofAdditionalInfo,
  SerializedMerkleProof,
  SerializedMerkleProofWithDetails
}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, OutgoingTxId, OutgoingTxRecipient, merkletree}
import io.iohk.scevm.sidechain.{CrossChainSignaturesService => DomainService, _}
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.Generators.bigIntGen
import io.iohk.scevm.testing.{CryptoGenerators, IOSupport, NormalPatience}
import io.iohk.scevm.trustlesssidechain.RegisterValidatorSignedMessage
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime.TimestampSeconds
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.InstantToUnixTimestamp
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import java.security.SecureRandom
import java.time.Instant

class SidechainControllerSpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks
    with NormalPatience
    with DiffShouldMatcher {

  implicit val candidateRegistrationEntryMatcher: SeqMatcher[CandidateRegistrationEntry] =
    ObjectMatcher.seq[CandidateRegistrationEntry].byValue(e => (e.utxo, e.mainchainPubKey, e.stakeDelegation))

  private val epochParameterVariants =
    Gen.oneOf(
      Gen.const(SidechainEpochParam.Latest),
      Gen.choose(1L, Long.MaxValue).map(n => SidechainEpochParam.ByEpoch(SidechainEpoch(n)))
    )

  "should return candidates" in {
    val (mainchainPrivKey, mainchainPubKey) = (for {
      bigInt <- bigIntGen
      keyPair = EdDSA.generateKeyPair(new SecureRandom(bigInt.toByteArray))
    } yield keyPair).sample.get
    val invalidPendingCandidate = registrationGen(
      isPending = true,
      epoch = MainchainEpoch(0),
      blockNumber = MainchainBlockNumber(0),
      eddsaPriv = mainchainPrivKey,
      pendingChange = Some(PendingRegistration(txInput.txHash, MainchainEpoch(1)))
    ).sample.get.copy(sidechainPubKey = ECDSA.PublicKey.Zero, crossChainPubKey = ECDSA.PublicKey.Zero)
    val invalidRegisteredCandidate = registrationGen(
      isPending = false,
      epoch = MainchainEpoch(0),
      blockNumber = MainchainBlockNumber(0),
      eddsaPriv = mainchainPrivKey
    ).sample.get.copy(sidechainPubKey = ECDSA.PublicKey.Zero, crossChainPubKey = ECDSA.PublicKey.Zero)
    val validPendingCandidates = registrationGen(
      isPending = true,
      epoch = MainchainEpoch(0),
      blockNumber = MainchainBlockNumber(0),
      eddsaPriv = mainchainPrivKey,
      pendingChange = Some(PendingRegistration(txInput.txHash, MainchainEpoch(1)))
    ).sample.get
    val validRegisteredCandidates = registrationGen(
      isPending = false,
      epoch = MainchainEpoch(0),
      blockNumber = MainchainBlockNumber(0),
      eddsaPriv = mainchainPrivKey
    ).sample.get

    val leaderCandidate = LeaderCandidate(
      mainchainPubKey,
      NonEmptyList.of(
        invalidPendingCandidate,
        invalidRegisteredCandidate,
        validPendingCandidates,
        validRegisteredCandidates
      )
    )

    val controller = createSidechainController[ECDSA](
      mainchainDataSource = MainchainDataSourceStub(
        Some(EpochData(EpochNonce(0), List(leaderCandidate)))
      )
    )

    val expected = List(
      generateCandidateDescription(
        mainchainPubKey,
        invalidPendingCandidate,
        CandidateRegistrationStatus.Invalid,
        invalidReasons = Some(
          Seq(
            "Invalid mainchain signature for utxo cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0",
            "Invalid sidechain signature for utxo cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0",
            "Invalid cross-chain signature for utxo cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0"
          )
        )
      ),
      generateCandidateDescription(
        mainchainPubKey,
        invalidRegisteredCandidate,
        CandidateRegistrationStatus.Invalid,
        invalidReasons = Some(
          Seq(
            "Invalid mainchain signature for utxo cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0",
            "Invalid sidechain signature for utxo cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0",
            "Invalid cross-chain signature for utxo cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0"
          )
        )
      ),
      generateCandidateDescription(
        mainchainPubKey,
        validPendingCandidates,
        CandidateRegistrationStatus.Pending,
        Some(EffectiveFrom(SidechainEpoch(1), MainchainEpoch(1)))
      ),
      generateCandidateDescription(mainchainPubKey, validRegisteredCandidates, CandidateRegistrationStatus.Active)
    )

    val response = controller.getCandidates(GetCandidatesRequest(SidechainEpochParam.ByEpoch(SidechainEpoch(1))))
    response.ioValue.value.candidates shouldMatchTo expected

    val response2 = controller.getCandidates(GetCandidatesRequest(SidechainEpochParam.Latest))
    response2.ioValue.value.candidates shouldMatchTo expected
  }

  "should return current (by SystemTime) candidates" in {
    val (mainchainPrivKey1, mainchainPubKey1) = EdDSA.generateKeyPair(new SecureRandom(Array.empty))
    val (mainchainPrivKey2, mainchainPubKey2) = EdDSA.generateKeyPair(new SecureRandom(Array.empty))
    val registrationData1 =
      registrationGen(
        isPending = true,
        MainchainEpoch(0),
        MainchainBlockNumber(0),
        mainchainPrivKey1,
        pendingChange = Some(PendingRegistration(txInput.txHash, MainchainEpoch(1)))
      ).sample.get
    val registrationData2 =
      registrationGen(
        isPending = false,
        MainchainEpoch(0),
        MainchainBlockNumber(0),
        mainchainPrivKey2,
        pendingChange = Some(PendingDeregistration(txInput.txHash, MainchainEpoch(1)))
      ).sample.get
    val candidates = List(
      datasource.LeaderCandidate(mainchainPubKey1, NonEmptyList.of(registrationData1)),
      datasource.LeaderCandidate(mainchainPubKey2, NonEmptyList.of(registrationData2))
    )

    val epochDerivation   = MainchainEpochDerivationStub()
    val currentSlotNumber = systemTime.realTime().map(epochDerivation.getMainchainSlot).ioValue.number
    val dataSource = new MainchainDataSource[IO, ECDSA] {
      override def getEpochData(epoch: MainchainEpoch): IO[Option[EpochData[ECDSA]]] =
        IO.raiseError(new RuntimeException(s"Expected request by MainchainSlot not before $currentSlotNumber"))

      override def getMainchainData(slot: MainchainSlot): IO[Option[EpochData[ECDSA]]] =
        if (currentSlotNumber <= slot.number && slot.number <= currentSlotNumber + 5)
          IO.pure(Some(EpochData(EpochNonce(0), candidates)))
        else IO.raiseError(new RuntimeException(s"Expected request by MainchainSlot not before $currentSlotNumber"))

      override def getLatestBlockInfo: IO[Option[MainchainBlockInfo]] = ???

      override def getStableBlockInfo(currentSlot: MainchainSlot): IO[Option[MainchainBlockInfo]] = ???
    }
    val controller =
      createSidechainController[ECDSA](
        mainchainEpochDerivation = epochDerivation,
        mainchainDataSource = dataSource
      )

    val response = controller.getCurrentCandidates.ioValue.value
    response.candidates shouldMatchTo List(
      generateCandidateDescription(
        mainchainPubKey1,
        registrationData1,
        CandidateRegistrationStatus.Pending,
        effectiveFrom = EffectiveFrom(SidechainEpoch(1), MainchainEpoch(1)).some,
        upcomingNewState = CandidateRegistrationStatus.Active
      ),
      generateCandidateDescription(
        mainchainPubKey2,
        registrationData2,
        CandidateRegistrationStatus.PendingDeregistration,
        effectiveFrom = EffectiveFrom(SidechainEpoch(1), MainchainEpoch(1)).some,
        upcomingNewState = CandidateRegistrationStatus.Deregistered
      )
    )
  }

  "should return error if there is no data for a given epoch" in {
    val controller = createSidechainController[ECDSA](
      committeeProvider = CommitteeProviderStub(Left(ElectionFailure.dataNotAvailable(0, 1)))
    )
    forAll(epochParameterVariants) { parameter =>
      controller.getCommittee(parameter).ioValue shouldMatchTo Left(
        JsonRpcError(-32000, Show[ElectionFailure].show(DataNotAvailable(0, 1)), None)
      )
    }
  }

  "should return error if there is not enough candidates for a given epoch" in {
    val controller = createSidechainController[ECDSA](
      committeeProvider = CommitteeProviderStub(Left(ElectionFailure.notEnoughCandidates))
    )
    forAll(epochParameterVariants) { parameter =>
      controller.getCommittee(parameter).ioValue shouldMatchTo Left(
        JsonRpcError(-32000, Show[ElectionFailure].show(ElectionFailure.notEnoughCandidates), None)
      )
    }
  }

  "should return committee" in {
    val committee = SidechainGenerators.committeeGenerator(5).sample.get.map(_.copy(stakeDelegation = Lovelace(100)))

    val controller = createSidechainController[ECDSA](
      committeeProvider = CommitteeProviderStub(Right(CommitteeElectionSuccess(committee)))
    )
    forAll(epochParameterVariants) { parameter =>
      val response = controller.getCommittee(parameter)
      response.ioValue.value.committee shouldMatchTo committee
        .map(vc => GetCommitteeCandidate(vc.pubKey, vc.stakeDelegation))
        .toList
    }
  }

  "should return empty status if there is no mainchain block" in {
    val controller = createSidechainController[ECDSA](
      statusProvider = MainchainStatusProviderStub[IO](MainchainStatus(None, None))
    )
    controller.getStatus().ioValue shouldMatchTo Left(
      SidechainController.MainchainDataNotAvailableError
    )

  }

  "should return status with latest mainchain block info" in {
    val mainchainBestBlockInfo = MainchainBlockInfo(
      MainchainBlockNumber(10),
      BlockHash(ByteString(Hex.decodeAsArrayUnsafe("ABCD"))),
      MainchainEpoch(10),
      MainchainSlot(10),
      Instant.EPOCH
    )
    val mainchainStableBlockInfo = MainchainBlockInfo(
      MainchainBlockNumber(1),
      BlockHash(ByteString(Hex.decodeAsArrayUnsafe("ABC"))),
      MainchainEpoch(1),
      MainchainSlot(1),
      Instant.EPOCH
    )
    val latestSidechainBlock = {
      val block = obftBlockGen.sample.get
      block.copy(
        header = block.header.copy(
          number = BlockNumber(2),
          slotNumber = Slot(2),
          unixTimestamp = Instant.EPOCH.toTs
        )
      )
    }
    val stableSidechainBlock = latestSidechainBlock.copy(
      header = latestSidechainBlock.header.copy(
        number = BlockNumber(1),
        slotNumber = Slot(1),
        unixTimestamp = Instant.EPOCH.toTs
      )
    )
    val controller = createSidechainController[ECDSA](
      statusProvider =
        MainchainStatusProviderStub[IO](MainchainStatus(Some(mainchainBestBlockInfo), Some(mainchainStableBlockInfo))),
      bestBlock = latestSidechainBlock,
      stableBlock = stableSidechainBlock
    )
    controller.getStatus().ioValue.value shouldMatchTo GetStatusResponse(
      SidechainData(
        SidechainBlockData(
          latestSidechainBlock.number,
          latestSidechainBlock.hash,
          latestSidechainBlock.header.unixTimestamp.toTimestampSeconds
        ),
        SidechainBlockData(
          stableSidechainBlock.number,
          stableSidechainBlock.hash,
          stableSidechainBlock.header.unixTimestamp.toTimestampSeconds
        ),
        SidechainEpochDerivationStub[IO]().getSidechainEpoch(latestSidechainBlock.header.slotNumber),
        EpochPhase.Regular,
        latestSidechainBlock.header.slotNumber,
        TimestampSeconds.fromSeconds(1500000240L)
      ),
      MainchainData(
        MainchainBlockData(
          mainchainBestBlockInfo.number,
          BlockHash(ByteString(Hex.decodeAsArrayUnsafe("ABCD"))),
          mainchainBestBlockInfo.instant.toTs.toTimestampSeconds
        ),
        MainchainBlockData(
          mainchainStableBlockInfo.number,
          BlockHash(ByteString(Hex.decodeAsArrayUnsafe("ABC"))),
          mainchainStableBlockInfo.instant.toTs.toTimestampSeconds
        ),
        mainchainBestBlockInfo.epoch,
        mainchainBestBlockInfo.slot,
        TimestampSeconds.fromSeconds(1000264000L)
      )
    )
  }

  "should return cross-chain signatures (only handover)" in {
    forAll(epochParameterVariants, CryptoGenerators.ecdsaKeyPairGen) { case (parameter, (prvKey, pubKey)) =>
      val signature = prvKey.sign(Blake2bHash32.hash(ByteString.empty).bytes)
      val controller = createSidechainController[ECDSA](
        crossChainSignaturesService = CrossChainSignaturesServiceStub[IO](
          Right(
            CrossChainSignatures(
              sidechainParams,
              DomainService
                .CommitteeHandoverSignatures(
                  List(pubKey),
                  None,
                  List(DomainService.MemberSignature(pubKey, signature))
                ),
              Nil
            )
          )
        )
      )
      controller.getEpochSignatures(parameter).ioValue.value shouldMatchTo GetSignaturesResponse(
        sidechainParams,
        CommitteeHandoverSignatures(List(pubKey), None, List(CrossChainSignaturesEntry(pubKey, signature.toBytes))),
        Nil
      )
    }
  }

  "should return cross-chain signatures (both handover & transactions)" in {
    forAll(epochParameterVariants, CryptoGenerators.ecdsaKeyPairGen) { case (parameter, (prvKey, pubKey)) =>
      val handoverSignature     = prvKey.sign(Blake2bHash32.hash(ByteString.empty).bytes)
      val transactionsSignature = prvKey.sign(Blake2bHash32.hash(ByteString(1)).bytes)
      val controller = createSidechainController[ECDSA](
        crossChainSignaturesService = CrossChainSignaturesServiceStub[IO](
          Right(
            CrossChainSignatures(
              sidechainParams,
              DomainService
                .CommitteeHandoverSignatures(
                  List(pubKey),
                  None,
                  List(DomainService.MemberSignature(pubKey, handoverSignature))
                ),
              List(
                DomainService.OutgoingTransactionSignatures(
                  RootHash(ByteString.empty),
                  None,
                  List(DomainService.MemberSignature(pubKey, transactionsSignature))
                )
              )
            )
          )
        )
      )
      controller.getEpochSignatures(parameter).ioValue.value shouldMatchTo GetSignaturesResponse(
        sidechainParams,
        CommitteeHandoverSignatures(
          List(pubKey),
          None,
          List(CrossChainSignaturesEntry(pubKey, handoverSignature.toBytes))
        ),
        List(
          OutgoingTransactionsSignatures(
            RootHash(ByteString.empty),
            None,
            List(CrossChainSignaturesEntry(pubKey, transactionsSignature.toBytes))
          )
        )
      )
    }
  }

  "should return sidechain parameters" in {
    val controller = createSidechainController[ECDSA]()
    val actual     = controller.getSidechainParams().ioValue
    assert(
      actual == GetSidechainParamsResponse(
        initializationParams.initializationConfig.genesisMintUtxo,
        initializationParams.initializationConfig.genesisCommitteeUtxo,
        initializationParams.sidechainParams.genesisHash,
        initializationParams.sidechainParams.chainId,
        SidechainParamsThreshold(
          initializationParams.sidechainParams.thresholdNumerator,
          initializationParams.sidechainParams.thresholdDenominator
        )
      )
    )
  }

  "should return merkle proof" in {
    val serializedMerkleProof = SerializedMerkleProofWithDetails(
      SerializedMerkleProof(ByteString(1, 2, 3)),
      MerkleProofAdditionalInfo(
        merkletree.RootHash(ByteString(1)),
        OutgoingTransaction(domain.Token(5), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(1L))
      )
    )
    val controller = createSidechainController[ECDSA](merkleProofService =
      (_: SidechainEpoch, _: transactions.OutgoingTxId) => IO.pure(Some(serializedMerkleProof))
    )
    val actual = controller.getOutgoingTxMerkleProof(SidechainEpochParam.Latest, OutgoingTxId(1)).ioValue
    assert(
      actual == Right(
        GetMerkleProofResponse(
          Some(
            GetMerkleProofData(
              serializedMerkleProof.merkleProof,
              GetMerkleProofInfoResponse(
                serializedMerkleProof.details.outgoingTransaction,
                serializedMerkleProof.details.currentRootHash
              )
            )
          ),
          SidechainEpoch(1)
        )
      )
    )
  }

  "should return error for missing certificates to upload if the chain is not initialized" in {
    val errorMsg = "Could not find any committee nft. Is the sidechain initialized?"
    val controller =
      createSidechainController[ECDSA](missingCertificatesResolver = _ => IO.pure(Left(errorMsg)))

    val result = controller.getSignaturesToUpload(None).ioValue
    assert(
      result == Left(JsonRpcError(-32000, errorMsg, None))
    )
  }

  "should return missing certificate to upload" in {
    val controller =
      createSidechainController[ECDSA](missingCertificatesResolver =
        _ => IO.pure(List(SidechainEpoch(1) -> List(merkletree.RootHash(Hex.decodeUnsafe("aaeeff")))).asRight[String])
      )

    val result = controller.getSignaturesToUpload(None).ioValue
    assert(
      result == Right(List(EpochAndRootHashes(SidechainEpoch(1), List(Hex.decodeUnsafe("aaeeff")))))
    )

  }

  val txInput: UtxoId = UtxoId(
    MainchainTxHash(
      ByteString(Hex.decodeAsArrayUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"))
    ),
    0
  )

  private def validMainchainSignature(eddsaPriv: EdDSA.PrivateKey, sidechainPubKey: ECDSA.PublicKey) =
    eddsaPriv.sign(message(sidechainPubKey))

  private def message(sidechainPubKey: ECDSA.PublicKey): ByteString = {
    val message = RegisterValidatorSignedMessage(sidechainParams, sidechainPubKey, txInput)
    ByteString(Cbor.encode(DatumEncoder[RegisterValidatorSignedMessage].encode(message)).toByteArray)
  }

  private def validSidechainSignature(ecdsaPrivateKey: ECDSA.PrivateKey, sidechainPubKey: ECDSA.PublicKey) =
    ecdsaPrivateKey.sign(Blake2bHash32.hash(message(sidechainPubKey)).bytes)

  private def registrationGen(
      isPending: Boolean,
      epoch: MainchainEpoch,
      blockNumber: MainchainBlockNumber,
      eddsaPriv: EdDSA.PrivateKey,
      pendingChange: Option[PendingRegistrationChange] = None
  ): Gen[RegistrationData[ECDSA]] =
    for {
      (ecdsaPrv, ecdsaPub) <- CryptoGenerators.ecdsaKeyPairGen
      ecdsaSignature        = validSidechainSignature(ecdsaPrv, ecdsaPub)
      eddsaSig              = validMainchainSignature(eddsaPriv, ecdsaPub)
      amount               <- Gen.posNum[Long]
    } yield datasource.RegistrationData[ECDSA](
      txInput,
      List(txInput),
      ecdsaSignature.withoutRecoveryByte,
      Lovelace(amount),
      eddsaSig,
      ecdsaSignature.withoutRecoveryByte,
      ecdsaPub,
      ecdsaPub,
      UtxoInfo(
        txInput,
        epoch,
        blockNumber,
        MainchainSlot(MainchainBlockNumber.toLong(blockNumber)),
        0
      ),
      isPending,
      pendingChange
    )

  private def generateCandidateDescription(
      mainchainPubKey: MainchainPublicKey,
      leaderCandidate: RegistrationData[ECDSA],
      registrationStatus: CandidateRegistrationStatus,
      effectiveFrom: Option[EffectiveFrom] = None,
      invalidReasons: Option[Seq[String]] = None,
      upcomingNewState: CandidateRegistrationStatus = CandidateRegistrationStatus.Active
  ): CandidateRegistrationEntry =
    CandidateRegistrationEntry(
      leaderCandidate.sidechainPubKey,
      mainchainPubKey,
      leaderCandidate.sidechainPubKey,
      leaderCandidate.sidechainSignature,
      leaderCandidate.mainchainSignature,
      leaderCandidate.sidechainSignature,
      leaderCandidate.utxoInfo,
      leaderCandidate.stakeDelegation,
      registrationStatus,
      effectiveFrom.map(activeFrom =>
        UpcomingRegistrationChange(
          upcomingNewState,
          activeFrom,
          leaderCandidate.utxoInfo.utxoId.txHash
        )
      ),
      invalidReasons
    )
}

final case class MainchainStatusProviderStub[F[_]: Applicative](status: MainchainStatus)
    extends MainchainStatusProvider[F] {
  override def getStatus: F[MainchainStatus] = status.pure[F]
}

final case class PendingTransactionServicesStub[F[_]: Applicative](response: PendingTransactionsResponse)
    extends PendingTransactionsService[F] {
  override def getPendingTransactions: F[PendingTransactionsResponse] = response.pure[F]
}

final case class MainchainDataSourceStub[F[_]: Applicative, Scheme <: AbstractSignatureScheme](
    maybeEpochData: Option[EpochData[Scheme]]
) extends MainchainDataSource[F, Scheme] {
  override def getEpochData(epoch: MainchainEpoch): F[Option[EpochData[Scheme]]] = maybeEpochData.pure[F]

  override def getMainchainData(slot: MainchainSlot): F[Option[EpochData[Scheme]]] = maybeEpochData.pure[F]

  override def getLatestBlockInfo: F[Option[MainchainBlockInfo]] = ???

  override def getStableBlockInfo(currentSlot: MainchainSlot): F[Option[MainchainBlockInfo]] = ???
}

final case class CrossChainSignaturesServiceStub[F[_]: Applicative](
    response: Either[String, DomainService.CrossChainSignatures]
) extends CrossChainSignaturesService[F] {
  override def getSignatures(
      epoch: SidechainEpoch
  ): F[Either[String, DomainService.CrossChainSignatures]] =
    response.pure[F]
}
