package io.iohk.scevm.sidechain.transactions

import cats.data.OptionT
import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.BlockContext
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.DatumEncoder.EncoderOpsFromDatum
import io.iohk.scevm.plutus.{DatumCodec, DatumDecoder, DatumEncoder}
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.{CombinedMerkleProof, CombinedMerkleProofWithDetails}
import io.iohk.scevm.sidechain.transactions.MerkleProofService.{
  MerkleProofAdditionalInfo,
  SerializedMerkleProof,
  SerializedMerkleProofWithDetails,
  combinedMerkleProofDatumCodec
}
import io.iohk.scevm.sidechain.transactions.merkletree.{MerkleProof, Side}

import scala.language.implicitConversions

class MerkleProofService[F[_]: CurrentBranch.Signal: MonadCancelThrow](
    provider: MerkleProofProvider[F],
    worldStateBuilder: WorldStateBuilder[F]
) {
  def getProof(epoch: SidechainEpoch, txId: OutgoingTxId): F[Option[SerializedMerkleProofWithDetails]] =
    OptionT(
      CurrentBranch.best
        .flatMap(best =>
          worldStateBuilder.getWorldStateForBlock(best.stateRoot, BlockContext.from(best)).use { world =>
            provider.getProof(world)(epoch, txId)
          }
        )
    ).map { case CombinedMerkleProofWithDetails(currentMerkleRoot, combined) =>
      val bytes = DatumEncoder[CombinedMerkleProof]
        .encode(combined)
        .toCborBytes
      SerializedMerkleProofWithDetails(
        SerializedMerkleProof(bytes),
        MerkleProofAdditionalInfo(
          currentMerkleRoot,
          toOutgoingTransaction(combined.transaction)
        )
      )
    }.value

  private def toOutgoingTransaction(transaction: MerkleTreeEntry) =
    OutgoingTransaction(transaction.amount, transaction.recipient, transaction.index)
}

object MerkleProofService {

  @newtype final case class SerializedMerkleProof(bytes: ByteString)

  final case class MerkleProofAdditionalInfo(
      currentRootHash: merkletree.RootHash,
      outgoingTransaction: OutgoingTransaction
  )
  final case class SerializedMerkleProofWithDetails(
      merkleProof: SerializedMerkleProof,
      details: MerkleProofAdditionalInfo
  )

  implicit val sideCodec: DatumCodec[merkletree.Side] = DatumCodec.make(
    DatumEncoder[Int].contramap[merkletree.Side] {
      case Side.Left  => 0
      case Side.Right => 1
    },
    DatumDecoder[Int].mapEither {
      case 0 => Right(Side.Left)
      case 1 => Right(Side.Right)
      case other =>
        Left(
          DatumDecodeError(
            s"Couldn't decode ${merkletree.Side.getClass.getSimpleName}. Expected `0` or `1` but got $other"
          )
        )
    }
  )
  implicit val upCodec: DatumCodec[merkletree.Up]                             = DatumCodec.derive
  implicit val merkleProofCodec: DatumCodec[MerkleProof]                      = MerkleProof.deriving
  implicit val outgoingTransactionDatumCodec: DatumCodec[OutgoingTransaction] = DatumCodec.derive
  implicit val combinedMerkleProofDatumCodec: DatumCodec[CombinedMerkleProof] = DatumCodec.derive

}
