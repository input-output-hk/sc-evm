package io.iohk.scevm.sidechain

import cats.Monad
import cats.data.{EitherT, NonEmptyVector, StateT}
import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.EpochPhase.{Handover, Regular}
import io.iohk.scevm.domain.{Address, Nonce, SignedTransaction}
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.ledger.NonceProvider
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.CombinedMerkleProofWithDetails
import io.iohk.scevm.sidechain.transactions.{
  MerkleProofProviderImpl,
  OutgoingTxId,
  OutgoingTxRecipient,
  TransactionsMerkleTree
}
import io.iohk.scevm.testing.{CryptoGenerators, TestCoreConfigs}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import io.iohk.scevm.utils.SystemTime

import java.nio.charset.StandardCharsets

class ActiveBridgeSpec extends BridgeContractSpecBase {
  implicit val systemTime: SystemTime[IO] = SystemTime.liveF[IO].unsafeRunSync()

  private val chainId: ChainId                           = ChainId(0x4e)
  private val sidechainParams: SidechainParams           = SidechainFixtures.sidechainParams
  val (validator1Prv, validator1Pub)                     = CryptoGenerators.ecdsaKeyPairGen.sample.get
  val (validator2Prv, validator2Pub)                     = CryptoGenerators.ecdsaKeyPairGen.sample.get
  val (validatorCrossChain1Prv, validatorCrossChain1Pub) = CryptoGenerators.ecdsaKeyPairGen.sample.get
  private val committeeHandoverSigner                    = new CommitteeHandoverSigner[IO]

  private def createSignTransactionProvider(committee: NonEmptyVector[ValidLeaderCandidate[ECDSA]]) =
    new HandoverTransactionProviderImpl[IO, ECDSA](
      new WorldBasedOnlyNonceProvider[IO](TestCoreConfigs.blockchainConfig),
      new CommitteeProviderStub[IO, ECDSA](
        Right(CommitteeElectionSuccess(committee))
      ),
      bridgeContract,
      sidechainParams,
      committeeHandoverSigner
    )

  val mpProvider = new MerkleProofProviderImpl[IO](bridgeContract)

  "should produce valid merkle proof after handover with single transaction and single validator" in {
    val signTransactionProvider =
      createSignTransactionProvider(
        NonEmptyVector.of(ValidLeaderCandidate(validator1Pub, validatorCrossChain1Pub, Lovelace(10_000)))
      )
    val epoch = SidechainEpoch(1)
    val program = for {
      _                                                          <- bridgeContract.setContractState(epoch, Regular)
      txId                                                       <- bridgeContract.lock(100 * ConversionRate, OutgoingTxRecipient(Hex.decodeUnsafe("aff123")))
      _                                                          <- bridgeContract.setContractState(epoch, Handover)
      _                                                          <- fixture.setCoinbaseAccount(Address.fromPrivateKey(validator1Prv))
      _                                                          <- submitValidatorSignature(signTransactionProvider)(epoch, validator1Prv)
      merkleRootHashFromContract                                 <- bridgeContract.getMerkleRootForEpoch(epoch)
      CombinedMerkleProofWithDetails(currentHash, combinedProof) <- getMerkleProof(txId, epoch)
      _                                                           = assert(merkleRootHashFromContract.contains(currentHash))
    } yield TransactionsMerkleTree.verifyProof(currentHash)(combinedProof)

    val result = fixture.run(program).ioValue
    assert(result)
  }

  "should produce valid merkle proof after handover with single transaction and multiple validator" in {
    val epoch                   = SidechainEpoch(1)
    val stakes                  = NonEmptyVector.of(ValidLeaderCandidate(validator1Pub, validatorCrossChain1Pub, Lovelace(10_000)))
    val signTransactionProvider = createSignTransactionProvider(stakes)
    val program = for {
      _                                                          <- bridgeContract.setContractState(epoch, Regular)
      txId                                                       <- bridgeContract.lock(100 * ConversionRate, OutgoingTxRecipient(Hex.decodeUnsafe("aff123")))
      _                                                          <- bridgeContract.setContractState(epoch, Handover)
      _                                                          <- fixture.setCoinbaseAccount(Address.fromPrivateKey(validator1Prv))
      _                                                          <- submitValidatorSignature(signTransactionProvider)(epoch, validator1Prv)
      _                                                          <- fixture.setCoinbaseAccount(Address.fromPrivateKey(validator2Prv))
      _                                                          <- submitValidatorSignature(signTransactionProvider)(epoch, validator2Prv)
      CombinedMerkleProofWithDetails(currentHash, combinedProof) <- getMerkleProof(txId, epoch)
    } yield TransactionsMerkleTree.verifyProof(currentHash)(combinedProof)

    val result = fixture.run(program).ioValue
    assert(result)
  }

  "should produce valid merkle proof after handover with multiple transactions and single validator" in {
    val stakes                  = NonEmptyVector.of(ValidLeaderCandidate(validator1Pub, validatorCrossChain1Pub, Lovelace(10_000)))
    val signTransactionProvider = createSignTransactionProvider(stakes)
    val program = for {
      _      <- bridgeContract.setContractState(SidechainEpoch(1), Regular)
      txId1  <- bridgeContract.lock(100 * ConversionRate, OutgoingTxRecipient(Hex.decodeUnsafe("aff123")))
      txId2  <- bridgeContract.lock(100 * ConversionRate, OutgoingTxRecipient(Hex.decodeUnsafe("aff123")))
      txId3  <- bridgeContract.lock(101 * ConversionRate, OutgoingTxRecipient(Hex.decodeUnsafe("aff123")))
      txId4  <- bridgeContract.lock(102 * ConversionRate, OutgoingTxRecipient(Hex.decodeUnsafe("aff124")))
      _      <- bridgeContract.setContractState(SidechainEpoch(1), Handover)
      _      <- fixture.setCoinbaseAccount(Address.fromPrivateKey(validator1Prv))
      _      <- submitValidatorSignature(signTransactionProvider)(SidechainEpoch(1), validator1Prv)
      result <- List(txId1, txId2, txId3, txId4).traverse(getMerkleProof(_, SidechainEpoch(1)))
    } yield result.map { case CombinedMerkleProofWithDetails(currentHash, combined) =>
      TransactionsMerkleTree.verifyProof(currentHash)(combined)
    }

    val result = fixture.run(program).ioValue
    assert(result.forall(identity))
  }

  private def getMerkleProof(txId: OutgoingTxId, sidechainEpoch: SidechainEpoch) =
    StateT.inspectF[IO, WorldType, CombinedMerkleProofWithDetails](world =>
      mpProvider.getProof(world)(sidechainEpoch, txId).map(_.get)
    )

  private def submitValidatorSignature(signTransactionProvider: HandoverTransactionProvider[IO, ECDSA])(
      sidechainEpoch: SidechainEpoch,
      validatorPrvKey: ECDSA.PrivateKey
  ): StateT[IO, WorldType, ByteString] =
    StateT
      .inspectF[IO, WorldType, SignedTransaction](world =>
        EitherT(signTransactionProvider.getSignTx(sidechainEpoch, world, validatorPrvKey))
          .getOrElseF(IO.raiseError(new RuntimeException("error while creating sign transaction")))
          .map { systemTx =>
            val transaction = systemTx
              .toTransaction(
                world.getAccount(Address.fromPrivateKey(validatorPrvKey)).map(_.nonce).getOrElse(Nonce.Zero)
              )
            SignedTransaction.sign(transaction, validatorPrvKey, Some(systemTx.chainId))
          }
      )
      .flatMap(st => executeTransaction(st, chainId))
      .flatMapF {
        case Left(value) =>
          IO.raiseError(
            new RuntimeException(value.error.toString + new String(value.data.toArray, StandardCharsets.UTF_8))
          )
        case Right(value) => IO.pure(value)
      }
}

class WorldBasedOnlyNonceProvider[F[_]: Monad](
    blockchainConfig: BlockchainConfig
) extends NonceProvider[F] {
  def getNextNonce(address: Address, world: WorldType): F[Nonce] = {
    implicit val chainId: BlockchainConfig.ChainId = blockchainConfig.chainId

    def latestChainNonce: Nonce =
      world.getAccount(address).map(_.nonce).getOrElse(blockchainConfig.genesisData.accountStartNonce)

    latestChainNonce.pure[F]
  }
}
