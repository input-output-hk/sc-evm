package io.iohk.scevm.sidechain

import cats.MonadError
import cats.data.{EitherT, NonEmptyVector, OptionT}
import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, AnySignature}
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{Address, BlockContext, SidechainPublicKey, orderingECDSAPublicKey}
import io.iohk.scevm.exec.vm.{EvmCall, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry
import io.iohk.scevm.sidechain.CrossChainSignaturesService.BridgeContract.AddressWithSignature
import io.iohk.scevm.sidechain.CrossChainSignaturesService._
import io.iohk.scevm.sidechain.committee.SidechainCommitteeProvider
import io.iohk.scevm.sidechain.transactions.merkletree
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.trustlesssidechain.SidechainParams

class CrossChainSignaturesService[F[
    _
]: MonadCancelThrow: CurrentBranch.Signal, SignatureScheme <: AbstractSignatureScheme](
    sidechainParams: SidechainParams,
    bridgeContract: BridgeContract[EvmCall[F, *], SignatureScheme],
    committeeProvider: SidechainCommitteeProvider[F, SignatureScheme],
    worldStateBuilder: WorldStateBuilder[F]
) {
  def getSignatures(epoch: SidechainEpoch): F[Either[String, CrossChainSignatures]] =
    CurrentBranch
      .best[F]
      .flatMap(header =>
        worldStateBuilder
          .getWorldStateForBlock(header.stateRoot, BlockContext.from(header))
          .use(getSignaturesAtBlock(epoch, _))
      )

  private def getSignaturesAtBlock(epoch: SidechainEpoch, world: WorldType) =
    (for {
      currentCommittee   <- getCommitteePubKeys(epoch)
      handoverSignatures <- getHandoverSignatures(epoch, world, currentCommittee)
      transactionSignatures <- EitherT.liftF[F, String, List[OutgoingTransactionSignatures]](
                                 getOutgoingTransactionSignatures(epoch, world, currentCommittee)
                               )
    } yield CrossChainSignatures(
      sidechainParams,
      handoverSignatures,
      transactionSignatures
    )).value

  private def getHandoverSignatures(
      epoch: SidechainEpoch,
      world: WorldType,
      currentCommittee: NonEmptyVector[SidechainPublicKey]
  ): EitherT[F, String, CommitteeHandoverSignatures] =
    for {
      latestMerkleRootHash <- EitherT.liftF(findLatestMerkleRootHashForEpoch(epoch).run(world))
      nextCommittee        <- getCommitteePubKeys(epoch.next)
      handoverSignatures <- EitherT.liftF(
                              bridgeContract
                                .getHandoverSignatures(
                                  epoch,
                                  currentCommittee.map(c => Address.fromPublicKey(c)).toList
                                )
                                .run(world)
                            )
    } yield CommitteeHandoverSignatures(
      nextCommittee.toList.sorted,
      latestMerkleRootHash,
      matchSignaturesWithPubKeys(handoverSignatures, currentCommittee)
    )

  private def findLatestMerkleRootHashForEpoch(epoch: SidechainEpoch): EvmCall[F, Option[RootHash]] =
    OptionT(bridgeContract.getMerkleRoot(epoch)).semiflatMap {
      case MerkleRootEntry.NewMerkleRoot(rootHash, _) => rootHash.pure[EvmCall[F, *]]
      case MerkleRootEntry.PreviousRootReference(previousEntryEpoch) =>
        getMerkleRootOrFail(previousEntryEpoch).map(_.rootHash)
    }.value

  private def getOutgoingTransactionSignatures(
      epoch: SidechainEpoch,
      world: WorldType,
      currentCommittee: NonEmptyVector[SidechainPublicKey]
  ): F[List[OutgoingTransactionSignatures]] =
    bridgeContract
      .getMerkleRoot(epoch)
      .run(world)
      .flatMap {
        case Some(chainEntry: MerkleRootEntry.NewMerkleRoot) =>
          val addresses = currentCommittee.map(Address.fromPublicKey).toList
          for {
            transactionsSignature  <- bridgeContract.getOutgoingTransactionSignatures(epoch, addresses).run(world)
            previousMerkleRootHash <- getPreviousMerkleRootChainEntry(chainEntry).run(world)
            signatures              = matchSignaturesWithPubKeys(transactionsSignature, currentCommittee)
          } yield List(
            OutgoingTransactionSignatures(chainEntry.rootHash, previousMerkleRootHash.map(_.rootHash), signatures)
          )
        case _ => List.empty[OutgoingTransactionSignatures].pure[F]
      }

  private def getPreviousMerkleRootChainEntry(
      currentChainEntry: MerkleRootEntry.NewMerkleRoot
  ): EvmCall[F, Option[MerkleRootEntry.NewMerkleRoot]] =
    OptionT
      .fromOption[EvmCall[F, *]](currentChainEntry.previousEntryEpoch)
      .semiflatMap(epoch => getMerkleRootOrFail(epoch))
      .value

  private def getMerkleRootOrFail(epoch: SidechainEpoch): EvmCall[F, MerkleRootEntry.NewMerkleRoot] =
    OptionT(bridgeContract.getMerkleRoot(epoch))
      .collect { case e: MerkleRootEntry.NewMerkleRoot => e }
      .getOrElseF(
        MonadError[EvmCall[F, *], Throwable].raiseError(
          new IllegalStateException(
            show"Couldn't get the previous value for the merkle root chain entry from epoch: $epoch"
          )
        )
      )

  private def matchSignaturesWithPubKeys(
      addressesWithSignatures: List[AddressWithSignature[SignatureScheme#Signature]],
      currentCommittee: NonEmptyVector[SidechainPublicKey]
  ): List[MemberSignature] = {
    val addressToTxSig = addressesWithSignatures
      .map(addrWithSig => (addrWithSig.address, addrWithSig.signature))
      .toMap

    currentCommittee
      .map(vc => vc -> addressToTxSig.get(Address.fromPublicKey(vc)))
      .collect { case (vc, Some(sig)) =>
        MemberSignature(vc, sig)
      }
      .toList
      .sortBy(_.member)
  }

  private def getCommitteePubKeys(epoch: SidechainEpoch): EitherT[F, String, NonEmptyVector[SidechainPublicKey]] =
    EitherT(committeeProvider.getCommittee(epoch))
      .leftMap(_.show)
      .map(_.committee.map(_.pubKey))
}

object CrossChainSignaturesService {
  final case class CrossChainSignatures(
      sidechainParams: SidechainParams,
      committeeHandover: CommitteeHandoverSignatures,
      outgoingTransactions: List[OutgoingTransactionSignatures]
  )
  final case class MemberSignature(
      member: SidechainPublicKey,
      signatures: AnySignature
  )
  final case class CommitteeHandoverSignatures(
      nextCommitteePublicKeys: List[SidechainPublicKey],
      previousMerkleRoot: Option[RootHash],
      signatures: List[MemberSignature]
  )

  final case class OutgoingTransactionSignatures(
      transactions: merkletree.RootHash,
      previousMerkleRoot: Option[RootHash],
      signatures: List[MemberSignature]
  )

  trait BridgeContract[F[_], SignatureScheme <: AbstractSignatureScheme] {
    def getHandoverSignatures(
        epoch: SidechainEpoch,
        validators: List[Address]
    ): F[List[AddressWithSignature[SignatureScheme#Signature]]]
    def getOutgoingTransactionSignatures(
        epoch: SidechainEpoch,
        validators: List[Address]
    ): F[List[AddressWithSignature[SignatureScheme#Signature]]]
    def getMerkleRoot(epoch: SidechainEpoch): F[Option[MerkleRootEntry]]
  }
  object BridgeContract {
    final case class AddressWithSignature[CrossChainSignature](address: Address, signature: CrossChainSignature)
  }
}
