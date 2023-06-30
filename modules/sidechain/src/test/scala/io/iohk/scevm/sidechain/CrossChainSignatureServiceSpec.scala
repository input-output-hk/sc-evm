package io.iohk.scevm.sidechain

import cats.Id
import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.IO
import cats.syntax.all._
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import fs2.concurrent.Signal
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{Address, orderingECDSAPublicKey}
import io.iohk.scevm.exec.vm.IOEvmCall
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry.NewMerkleRoot
import io.iohk.scevm.sidechain.CrossChainSignaturesService.BridgeContract.AddressWithSignature
import io.iohk.scevm.sidechain.CrossChainSignaturesService.{
  BridgeContract,
  CommitteeHandoverSignatures,
  CrossChainSignatures,
  MemberSignature,
  OutgoingTransactionSignatures
}
import io.iohk.scevm.sidechain.EVMTestUtils.EVMFixture
import io.iohk.scevm.sidechain.certificate.MerkleRootSigner
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider.CommitteeElectionSuccess
import io.iohk.scevm.sidechain.testing.SidechainDiffxInstances._
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.testing.SidechainGenerators.{
  committeeGenerator,
  committeeGeneratorWithPrivateKeys,
  outgoingTransactionGen
}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, TransactionsMerkleTree}
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.trustlesssidechain.SidechainParams
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

class CrossChainSignatureServiceSpec
    extends AsyncWordSpec
    with ScalaFutures
    with IOSupport
    with Matchers
    with ScalaCheckPropertyChecks
    with IntegrationPatience
    with EitherValues
    with DiffShouldMatcher {

  private val sidechainParams: SidechainParams = SidechainFixtures.sidechainParams

  val fixture: EVMFixture = EVMFixture()

  "should return empty list if there are no signatures" in {
    forAll(for {
      committee     <- SidechainGenerators.committeeGenerator[ECDSA](5)
      nextCommittee <- SidechainGenerators.committeeGenerator[ECDSA](3)
    } yield (committee, nextCommittee)) { case (committee, nextCommittee) =>
      val service = createService(committee, nextCommittee, List.empty)
      val result  = service.getSignatures(SidechainEpoch(2)).ioValue
      result shouldBe Right(
        CrossChainSignatures(
          sidechainParams,
          CommitteeHandoverSignatures(
            nextCommittee.toList.sortBy(_.pubKey).map(_.pubKey),
            None,
            List.empty
          ),
          Nil
        )
      )
    }
  }

  "should return new committee signed by the current one" in {
    forAll(for {
      committee      <- committeeGeneratorWithPrivateKeys[ECDSA](5)
      nextCommittee  <- committeeGenerator[ECDSA](3)
      prevMerkleRoot <- Gen.option(SidechainGenerators.rootHashGen)
    } yield (committee, nextCommittee, prevMerkleRoot)) { case (committee, nextCommittee, prevMerkleRoot) =>
      val epoch = SidechainEpoch(2)
      val signatures =
        calculateSignatures(committee.toList, nextCommittee.toList, prevMerkleRoot, epoch)
      val previousEpoch = epoch.prev
      val service = createService(
        committee.map(_._1),
        nextCommittee,
        signatures.map(_._2),
        merkleRootHashes = prevMerkleRoot.toList
          .flatMap(mr =>
            List(
              previousEpoch -> NewMerkleRoot(mr, None),
              epoch         -> MerkleRootEntry.PreviousRootReference(previousEpoch)
            )
          )
          .toMap,
        previousMerkleEntryIndex = prevMerkleRoot.map(_ => previousEpoch)
      )
      val result = service.getSignatures(epoch).ioValue
      val expectedResult = CrossChainSignatures(
        sidechainParams,
        CommitteeHandoverSignatures(
          nextCommittee.toList.map(_.pubKey).sorted,
          prevMerkleRoot,
          signatures
            .map { case (pubKey, addressWithSignature) =>
              MemberSignature(
                pubKey,
                addressWithSignature.signature
              )
            }
            .sortBy(_.member)
        ),
        Nil
      )
      result.value shouldMatchTo expectedResult
    }
  }

  "should return new committee signed by the current one with previous merkle root hash from more distant past" in {
    forAll(for {
      committee      <- committeeGeneratorWithPrivateKeys[ECDSA](5)
      nextCommittee  <- committeeGenerator[ECDSA](3)
      prevMerkleRoot <- Gen.option(SidechainGenerators.rootHashGen)
    } yield (committee, nextCommittee, prevMerkleRoot)) { case (committee, nextCommittee, prevMerkleRoot) =>
      val epoch = SidechainEpoch(2)
      val signatures =
        calculateSignatures(committee.toList, nextCommittee.toList, prevMerkleRoot, epoch)
      val service = createService(
        committee.map(_._1),
        nextCommittee,
        signatures.map(_._2),
        merkleRootHashes = prevMerkleRoot.toList
          .flatMap(mr =>
            List(
              SidechainEpoch(0) -> NewMerkleRoot(mr, None),
              SidechainEpoch(1) -> MerkleRootEntry.PreviousRootReference(SidechainEpoch(0)),
              epoch             -> MerkleRootEntry.PreviousRootReference(SidechainEpoch(0))
            )
          )
          .toMap,
        previousMerkleEntryIndex = prevMerkleRoot.map(_ => SidechainEpoch(0))
      )
      val result = service.getSignatures(epoch).ioValue
      val expectedResult = CrossChainSignatures(
        sidechainParams,
        CommitteeHandoverSignatures(
          nextCommittee.toList.map(_.pubKey).sorted,
          prevMerkleRoot,
          signatures
            .map { case (pubKey, addressWithSignature) =>
              MemberSignature(
                pubKey,
                addressWithSignature.signature
              )
            }
            .sortBy(_.member)
        ),
        Nil
      )
      result.value shouldMatchTo expectedResult
    }
  }

  "should return data not available when requesting future data" in {
    forAll(for {
      committee     <- SidechainGenerators.committeeGenerator[ECDSA](5)
      nextCommittee <- SidechainGenerators.committeeGenerator[ECDSA](3)
    } yield (committee, nextCommittee)) { case (committee, nextCommittee) =>
      val service = createService(committee, nextCommittee, List.empty)
      val result  = service.getSignatures(SidechainEpoch(3)).ioValue
      result shouldMatchTo Left(ElectionFailure.dataNotAvailable(1, 4).show)
    }
  }

  "should return new committee together with merkle root hash, both signed by current committee" in {
    forAll(for {
      committee      <- committeeGeneratorWithPrivateKeys[ECDSA](5)
      nextCommittee  <- committeeGenerator[ECDSA](3)
      outgoingTx     <- outgoingTransactionGen
      prevMerkleRoot <- Gen.option(SidechainGenerators.rootHashGen)
    } yield (committee, nextCommittee, outgoingTx, prevMerkleRoot)) {
      case (committee, nextCommittee, outgoingTx, previousMerkleRootHash) =>
        val (merkleRootHash, txSignatures) =
          calculateTxSignatures(committee.toList, NonEmptyList.one(outgoingTx), previousMerkleRootHash)
        val epoch = SidechainEpoch(2)
        val handoverSignatures =
          calculateSignatures(committee.toList, nextCommittee.toList, Some(merkleRootHash), epoch)
        val service = createService(
          committee.map(_._1),
          nextCommittee,
          handoverSignatures.map(_._2),
          txSignatures.map(_._2),
          merkleRootHashes = Map(
            epoch -> NewMerkleRoot(
              merkleRootHash,
              previousMerkleRootHash.map(_ => epoch.prev)
            )
          ) ++ previousMerkleRootHash.map(pm => epoch.prev -> NewMerkleRoot(pm, None)).toList.toMap
        )

        val result = service.getSignatures(epoch).ioValue
        val expectedResult = CrossChainSignatures(
          sidechainParams,
          CommitteeHandoverSignatures(
            nextCommittee.toList.map(_.pubKey).sorted,
            Some(merkleRootHash),
            handoverSignatures
              .map { case (pubKey, addressWithSignature) =>
                MemberSignature(
                  pubKey,
                  addressWithSignature.signature
                )
              }
              .sortBy(_.member)
          ),
          List(
            OutgoingTransactionSignatures(
              merkleRootHash,
              previousMerkleRootHash,
              txSignatures
                .map { case (pubKey, addressWithSignature) =>
                  MemberSignature(pubKey, addressWithSignature.signature)
                }
                .sortBy(_.member)
            )
          )
        )
        result.value shouldMatchTo expectedResult
    }
  }

  private def calculateSignatures(
      committee: List[(ValidLeaderCandidate[ECDSA], ECDSA.PrivateKey)],
      nextCommittee: List[ValidLeaderCandidate[ECDSA]],
      prevRoot: Option[RootHash],
      epoch: SidechainEpoch
  ) =
    committee.map { case (ValidLeaderCandidate(pubKey, _, _), prv) =>
      pubKey -> AddressWithSignature(
        Address.fromPublicKey(pubKey),
        new certificate.CommitteeHandoverSigner[Id].sign(
          sidechainParams,
          NonEmptyList.fromListUnsafe(nextCommittee).map(_.pubKey),
          prevRoot,
          epoch
        )(prv)
      )
    }

  private def calculateTxSignatures(
      committee: List[(ValidLeaderCandidate[ECDSA], ECDSA.PrivateKey)],
      txs: NonEmptyList[OutgoingTransaction],
      previousMerkleRootHash: Option[RootHash]
  ) = {
    val rootHash = TransactionsMerkleTree(previousMerkleRootHash, txs).rootHash
    val signatures = committee.map { case (ValidLeaderCandidate(pubKey, _, _), prv) =>
      val signedRootHash = MerkleRootSigner.sign(previousMerkleRootHash, sidechainParams, txs)(prv)
      assert(signedRootHash.rootHash == rootHash)
      pubKey -> AddressWithSignature(
        Address.fromPublicKey(pubKey),
        signedRootHash.signature
      )
    }
    (rootHash, signatures)
  }

  private def createService(
      committee: NonEmptyVector[ValidLeaderCandidate[ECDSA]],
      nextCommittee: NonEmptyVector[ValidLeaderCandidate[ECDSA]],
      handoverSignatures: List[BridgeContract.AddressWithSignature[ECDSASignature]],
      outgoingTxSignatures: List[BridgeContract.AddressWithSignature[ECDSASignature]] = Nil,
      merkleRootHashes: Map[SidechainEpoch, MerkleRootEntry] = Map.empty,
      previousMerkleEntryIndex: Option[SidechainEpoch] = None
  ) = {
    val bridge = new CrossChainSignaturesService.BridgeContract[IOEvmCall, ECDSA] {
      override def getHandoverSignatures(
          epoch: SidechainEpoch,
          validators: List[Address]
      ): IOEvmCall[List[BridgeContract.AddressWithSignature[ECDSASignature]]] = IOEvmCall.pure(handoverSignatures)

      override def getOutgoingTransactionSignatures(
          epoch: SidechainEpoch,
          validators: List[Address]
      ): IOEvmCall[List[BridgeContract.AddressWithSignature[ECDSASignature]]] = IOEvmCall.pure(outgoingTxSignatures)

      override def getMerkleRoot(epoch: SidechainEpoch): IOEvmCall[Option[MerkleRootEntry]] =
        IOEvmCall.pure(merkleRootHashes.get(epoch))
    }
    implicit val currentBestSignal: CurrentBranch.Signal[IO] =
      Signal.constant[IO, CurrentBranch](CurrentBranch(fixture.genesisBlock.header))
    val service = new CrossChainSignaturesService[IO, ECDSA](
      sidechainParams,
      bridge,
      (epoch: SidechainEpoch) =>
        if (epoch == SidechainEpoch(2)) {
          IO.pure(Right(CommitteeElectionSuccess(committee)))
        } else if (epoch == SidechainEpoch(3)) {
          IO.pure(Right(CommitteeElectionSuccess(nextCommittee)))
        } else {
          IO.pure(Left(ElectionFailure.dataNotAvailable(1, epoch.number)))
        },
      fixture.proxyBuilder
    )
    service
  }

  implicit val loggerFactorId: LoggerFactory[Id] = NoOpFactory[Id]

}
