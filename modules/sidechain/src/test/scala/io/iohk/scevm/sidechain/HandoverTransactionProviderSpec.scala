package io.iohk.scevm.sidechain

import cats.data.{NonEmptyVector, StateT}
import cats.effect.IO
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.EpochPhase.Handover
import io.iohk.scevm.domain.TransactionOutcome.SuccessOutcome
import io.iohk.scevm.domain.{Address, Nonce, ObftGenesisAccount, ReceiptBody, Slot, Token, Transaction, Type02Receipt}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.{IOEvmCall, TransactionSimulator, WorldType}
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.ledger.{BloomFilter, NonceProvider}
import io.iohk.scevm.sidechain.EVMTestUtils.EVMFixture
import io.iohk.scevm.sidechain.SidechainFixtures.SidechainEpochDerivationStub
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.consensus.HandoverSignatureValidator
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, OutgoingTxId, OutgoingTxRecipient}
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing._
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class HandoverTransactionProviderSpec
    extends AsyncWordSpec
    with ScalaFutures
    with IOSupport
    with Matchers
    with EitherValues
    with NormalPatience {

  private val (prvKey, pubKey)                     = ecdsaKeyPairGen.sample.get
  private val (crossChainPrvKey, crossChainPubKey) = ecdsaKeyPairGen.sample.get
  private val address: Address                     = Address.fromPublicKey(pubKey)
  private val sidechainParams                      = SidechainFixtures.sidechainParams
  private val epoch                                = SidechainEpoch(10)
  private val epochDerivation                      = SidechainEpochDerivationStub[IO](slotsInEpoch = 100, stabilityParameter = 5)
  private val handoverPhaseSlot                    = Slot(1090)

  private val currentCommittee: NonEmptyVector[ValidLeaderCandidate[ECDSA]] =
    SidechainGenerators.committeeGenerator[ECDSA](3).sample.get :+ ValidLeaderCandidate[ECDSA](
      pubKey,
      crossChainPubKey,
      Lovelace(1)
    )
  private val nextEpochCommittee = SidechainGenerators.committeeGenerator[ECDSA](3).sample.get
  private val world              = TestInMemoryWorldState.worldStateGen.sample.get
  private val bridgeAddress      = Address("0x696f686b2e6d616d626100000000000000000000")

  private val fixture = EVMFixture(
    bridgeAddress -> EVMTestUtils.loadContractCodeFromFile("Bridge.bin-runtime", 10000000000L),
    address       -> ObftGenesisAccount(balance = 100000000L, code = None, nonce = None, storage = Map.empty)
  )

  private val nonceProvider: NonceProvider[IO] = (_, _) => IO.pure(Nonce(42))

  private val bridgeContract =
    new BridgeContract[IOEvmCall, ECDSA](
      TestCoreConfigs.blockchainConfig.chainId,
      bridgeAddress,
      TransactionSimulator[IO](TestCoreConfigs.blockchainConfig, EVMTestUtils.vm, IO.pure(_))
    )

  private val committeeProvider: SidechainCommitteeProvider[IO, ECDSA] = e =>
    if (e == epoch) IO.pure(Right(CommitteeElectionSuccess[ECDSA](currentCommittee)))
    else if (e == epoch.next) IO.pure(Right(CommitteeElectionSuccess(nextEpochCommittee)))
    else IO.raiseError(new Exception(s"Unexpected invocation for epoch $e"))

  "should make a signature tx for the next epoch committee in handover phase" in {
    val provider = new HandoverTransactionProviderImpl[IO, ECDSA](
      nonceProvider,
      committeeProvider,
      bridgeContract,
      sidechainParams,
      new CommitteeHandoverSigner[IO]
    )
    val validator =
      new HandoverSignatureValidator[IO, ECDSA](
        bridgeAddress,
        sidechainParams,
        bridgeContract,
        epochDerivation,
        committeeProvider.getCommittee,
        new CommitteeHandoverSigner[IO]
      )

    val header = fixture.genesisBlock.header

    val program = for {
      _ <- fixture.updateWorld(state =>
             IO(
               BridgeContract.prepareState(bridgeAddress)(
                 state.modifyBlockContext(_.copy(slotNumber = handoverPhaseSlot)),
                 epoch,
                 Handover
               )
             )
           )
      getTxResult      <- fixture.withWorld(provider.getSignTx(epoch, _, crossChainPrvKey))
      tx                = getTxResult.value
      _                <- fixture.setCoinbaseAccount(address)
      executionResult  <- executeTx(address, tx.toTransaction(Nonce.Zero))
      validationResult <- fixture.withWorld(validator.validate(_, header, executionResult))
    } yield validationResult

    val result = fixture.run(program).ioValue

    result shouldBe Right(())
  }

  "should have a different result depending on the root hash" in {

    val getSignTx = (outgoingTransactionList: Seq[OutgoingTransaction], prevMerkleRoot: Option[RootHash]) =>
      new HandoverTransactionProviderImpl[IO, ECDSA](
        nonceProvider,
        committeeProvider,
        SidechainFixtures.bridgeContractStub(outgoingTransactionList, prevMerkleRoot),
        sidechainParams,
        new CommitteeHandoverSigner[IO]
      ).getSignTx(epoch, world, crossChainPrvKey).map(_.value)

    val result = for {
      txWithoutPreviousAndCurrentTxs <- getSignTx(Nil, None)
      txWithPreviousAndNoCurrentTxs  <- getSignTx(Nil, Some(RootHash(Hex.decodeUnsafe("ff" * 34))))
      txWithPreviousAndCurrentTxs <- getSignTx(
                                       Seq(
                                         OutgoingTransaction(
                                           Token(10),
                                           OutgoingTxRecipient.decodeUnsafe(
                                             "caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"
                                           ),
                                           OutgoingTxId(0)
                                         )
                                       ),
                                       Some(RootHash(Hex.decodeUnsafe("ff" * 34)))
                                     )
    } yield (txWithoutPreviousAndCurrentTxs, txWithPreviousAndNoCurrentTxs, txWithPreviousAndCurrentTxs)

    val (txWithoutPreviousAndCurrentTxs, txWithPreviousAndNoCurrentTxs, txWithPreviousAndCurrentTxs) = result.ioValue

    assert(txWithoutPreviousAndCurrentTxs != txWithPreviousAndNoCurrentTxs)
    assert(txWithoutPreviousAndCurrentTxs != txWithPreviousAndCurrentTxs)
    assert(txWithPreviousAndNoCurrentTxs != txWithPreviousAndCurrentTxs)

  }

  "should fail if committee provider return failure" in {
    val provider = new HandoverTransactionProviderImpl[IO, ECDSA](
      nonceProvider,
      _ => IO.pure(Left(ElectionFailure.dataNotAvailable(0, 1))),
      bridgeContract,
      sidechainParams,
      new CommitteeHandoverSigner[IO]
    )
    val result = provider.getSignTx(epoch, world, crossChainPrvKey).ioValue.left.value
    result shouldBe "Couldn't get committee for epoch 11, because of 'Currently no data is available on the main chain for this epoch (mainchain = 0, sidechain = 1)'"
  }

  private def executeTx(
      address: Address,
      transaction: Transaction
  ): StateT[IO, WorldType, BlockExecutionResult] =
    fixture
      .executeTransaction(
        transaction,
        address
      )
      .flatMapF(pr =>
        pr.error match {
          case Some(err) =>
            IO.raiseError(new RuntimeException(s"Unexpected transaction execution error: $err"))
          case None =>
            val receipt = ReceiptBody(SuccessOutcome, 0, BloomFilter.create(pr.logs), pr.logs)
            IO.pure(BlockExecutionResult(pr.world, 0, Seq(Type02Receipt(receipt))))
        }
      )
}
