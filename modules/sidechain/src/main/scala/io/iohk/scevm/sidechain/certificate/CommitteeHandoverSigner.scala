package io.iohk.scevm.sidechain.certificate

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{SidechainPublicKey, showForByteString}
import io.iohk.scevm.plutus.DatumEncoder.EncoderOpsFromDatum
import io.iohk.scevm.plutus._
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.certificate.CommitteeHandoverSigner.UpdateCommitteeMessage
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.{Blake2bHash32, EcdsaCompressedPubKey}
import org.typelevel.log4cats.{Logger, LoggerFactory}

class CommitteeHandoverSigner[F[_]: LoggerFactory: Monad] {

  implicit private val logger: Logger[F] = LoggerFactory[F].getLogger

  /** Signature schema should match with https://github.com/mlabs-haskell/trustless-sidechain#6-update--committee-hash
    * @param merkleRoot latest merkle root at the time of the signature. ie the root hash signed at the same time as
    *                   this handover, or the previous one if no outgoing transaction occurred.
    */
  def sign[SignatureScheme <: AbstractSignatureScheme](
      params: SidechainParams,
      nextCommittee: NonEmptyList[SidechainPublicKey],
      merkleRoot: Option[RootHash],
      epoch: SidechainEpoch
  )(prvKey: SignatureScheme#PrivateKey): F[SignatureScheme#Signature] =
    messageHash(params, nextCommittee, merkleRoot, epoch).map { hashedMsg =>
      prvKey.sign(hashedMsg.bytes)
    }

  def messageHash(
      params: SidechainParams,
      nextCommittee: NonEmptyList[SidechainPublicKey],
      merkleRoot: Option[RootHash],
      epoch: SidechainEpoch
  ): F[Blake2bHash32] = {
    val nextCommitteeCompressedKeys = nextCommittee
      .map(key => EcdsaCompressedPubKey(key.compressedBytes))
      .sortBy(key => Hex.toHexString(key.bytes))
    val datumMsg = DatumEncoder[UpdateCommitteeMessage].encode(
      UpdateCommitteeMessage(
        params,
        nextCommitteeCompressedKeys,
        merkleRoot,
        epoch
      )
    )
    Logger[F].debug(show"Datum for committee handover: $datumMsg") >>
      datumMsg.toCborBytes
        .pure[F]
        .flatTap { cborBytes =>
          //explicitly calling show because otherwise IJ marks import as unused
          Logger[F].debug(show"Committee handover message as cbor: ${cborBytes.show}")
        }
        .map(Blake2bHash32.hash)
  }

  implicit private val updateCommitteeMessageEncoder: DatumEncoder[UpdateCommitteeMessage] =
    (value: UpdateCommitteeMessage) =>
      ConstructorDatum(
        0,
        Vector(
          DatumEncoder[SidechainParams].encode(value.sidechainParams),
          DatumEncoder[NonEmptyList[EcdsaCompressedPubKey]].encode(value.newCommitteePubKeys),
          DatumEncoder[Option[RootHash]].encode(value.previousMerkleRoot),
          DatumEncoder[SidechainEpoch].encode(value.sidechainEpoch)
        )
      )
}
object CommitteeHandoverSigner {

  /* haskell equivalent:
   * data UpdateCommitteeMessage = UpdateCommitteeMessage
   *   { sidechainParams :: SidechainParams
   *   , newCommitteePubKeys :: [SidechainPubKey] -- sorted lexicographically
   *   , previousMerkleRoot :: Maybe ByteString
   *   , sidechainEpoch :: Integer
   *   }
   */
  final private case class UpdateCommitteeMessage(
      sidechainParams: SidechainParams,
      newCommitteePubKeys: NonEmptyList[EcdsaCompressedPubKey],
      previousMerkleRoot: Option[RootHash],
      sidechainEpoch: SidechainEpoch
  )
}
