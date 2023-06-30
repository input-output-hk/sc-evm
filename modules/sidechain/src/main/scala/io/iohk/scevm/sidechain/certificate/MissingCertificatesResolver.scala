package io.iohk.scevm.sidechain.certificate

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.syntax.all._
import cats.{Monad, MonadThrow, ~>}
import io.iohk.bytes.ByteString._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.MainchainActiveFlowDataSource
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.BlockContext
import io.iohk.scevm.exec.vm.EvmCall
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry.NewMerkleRoot
import io.iohk.scevm.sidechain.certificate.MissingCertificatesResolver.{
  BridgeContract,
  ChainNotInitializedException,
  MainchainDataProvider
}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.{SidechainEpoch, SidechainEpochDerivation}
import io.iohk.scevm.trustlesssidechain.cardano.{CommitteeNft, MerkleRootNft}

trait MissingCertificatesResolver[F[_]] {
  def getMissingCertificates(limit: Int): F[Either[String, List[(SidechainEpoch, List[RootHash])]]]
}

class MissingCertificatesResolverImpl[F[_]: Monad, G[_]: MonadThrow](
    sidechainEpochDerivation: SidechainEpochDerivation[F],
    mainchainDataProvider: MainchainDataProvider[F],
    bridgeContract: BridgeContract[G]
)(implicit liftFtoG: F ~> G)
    extends MissingCertificatesResolver[G] {
  def getMissingCertificates(limit: Int): G[Either[String, List[(SidechainEpoch, List[RootHash])]]] =
    (for {
      currentEpoch <- liftFtoG(sidechainEpochDerivation.getCurrentEpoch)
      committeeNft <- liftFtoG(mainchainDataProvider.getLastOnChainCommitteeNft()).flatMap {
                        case Some(value) => value.pure[G]
                        case None =>
                          MonadThrow[G].raiseError[CommitteeNft](
                            new ChainNotInitializedException()
                          )
                      }
      missingCertificates <-
        (committeeNft.sidechainEpoch.next.number until currentEpoch.number).toList.take(limit).traverse { epochNumber =>
          val epoch = SidechainEpoch(epochNumber)
          bridgeContract
            .getMerkleRootChainEntry(epoch)
            .map(mr => mr.map(_.rootHash).toList)
            .map(epoch -> _)
        }
      result <- liftFtoG(
                  OptionT(mainchainDataProvider.getLastOnChainMerkleRootHash())
                    .map { lastMerkleRootHash =>
                      missingCertificates match {
                        case (epoch, merkleRoots) :: tail =>
                          val missingRoots =
                            merkleRoots.reverse.takeWhile(_.value =!= lastMerkleRootHash.merkleRootHash).reverse
                          (epoch, missingRoots) :: tail
                        case Nil => Nil
                      }
                    }
                    .getOrElse(missingCertificates)
                )
    } yield result.asRight[String]).recover { case e: ChainNotInitializedException =>
      e.getMessage.asLeft
    }
}

object MissingCertificatesResolver {
  final class ChainNotInitializedException
      extends RuntimeException("Could not find any committee nft. Is the sidechain initialized?")

  trait MainchainDataProvider[F[_]] {
    def getLastOnChainCommitteeNft(): F[Option[CommitteeNft]]

    def getLastOnChainMerkleRootHash(): F[Option[MerkleRootNft]]
  }

  trait BridgeContract[F[_]] {
    def getMerkleRootChainEntry(
        epoch: SidechainEpoch
    ): F[Option[NewMerkleRoot]]
  }

  def apply[F[_]: Async: CurrentBranch.Signal, SignatureScheme <: AbstractSignatureScheme](
      sidechainEpochDerivation: SidechainEpochDerivation[F],
      activeFlowDataProvider: MainchainActiveFlowDataSource[F],
      bridgeContract: io.iohk.scevm.sidechain.BridgeContract[EvmCall[F, *], SignatureScheme],
      worldStateBuilder: WorldStateBuilder[F]
  ): MissingCertificatesResolver[F] = {
    val resolver = new MissingCertificatesResolverImpl[F, EvmCall[F, *]](
      sidechainEpochDerivation,
      new MissingCertificatesResolver.MainchainDataProvider[F] {
        override def getLastOnChainCommitteeNft(): F[Option[CommitteeNft]] =
          activeFlowDataProvider.getLatestCommitteeNft()

        override def getLastOnChainMerkleRootHash(): F[Option[MerkleRootNft]] =
          activeFlowDataProvider.getLatestOnChainMerkleRootNft()
      },
      bridgeContract
    )(Monad[F], MonadThrow[EvmCall[F, *]], EvmCall.liftK)
    limit =>
      CurrentBranch.best
        .flatMap(best =>
          worldStateBuilder
            .getWorldStateForBlock(best.stateRoot, BlockContext.from(best))
            .use(resolver.getMissingCertificates(limit).run)
        )
  }
}
