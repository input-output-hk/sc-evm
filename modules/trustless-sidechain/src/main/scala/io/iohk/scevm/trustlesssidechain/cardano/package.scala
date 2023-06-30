package io.iohk.scevm.trustlesssidechain

import cats.syntax.all._
import cats.{Eq, Show}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops.toCoercibleIdOps
import io.iohk.bytes.ByteString
import io.iohk.bytes.ByteString.eqForByteString
import io.iohk.ethereum.crypto.EdDSA
import io.iohk.ethereum.utils.{ByteStringUtils, Hex}
import io.iohk.scevm.domain._
import io.iohk.scevm.plutus.Datum
import io.iohk.scevm.sidechain.SidechainEpoch
import org.bouncycastle.crypto.digests.Blake2bDigest

import java.time.Instant
import scala.language.implicitConversions
import scala.util.Try

// scalastyle:off
package object cardano {
  @newtype final case class MainchainEpoch(number: Long) {
    def next: MainchainEpoch = MainchainEpoch(number + 1)
    def prev: MainchainEpoch = MainchainEpoch(number - 1)
  }
  object MainchainEpoch {
    implicit val show: Show[MainchainEpoch] = deriving
  }

  @newtype final case class MainchainBlockNumber(value: Long)
  object MainchainBlockNumber {
    implicit val show: Show[MainchainBlockNumber]       = deriving
    def toLong(blockNumber: MainchainBlockNumber): Long = blockNumber.value
  }

  @newtype final case class MainchainSlot(number: Long) {
    def prev: MainchainSlot = MainchainSlot(number - 1)
  }
  object MainchainSlot {
    implicit val show: Show[MainchainSlot] = deriving
  }

  @newtype final case class EpochNonce(value: BigInt)
  object EpochNonce {
    implicit val show: Show[EpochNonce] = deriving
  }

  @newtype final case class Blake2bHash28(bytes: ByteString)
  object Blake2bHash28 {
    implicit val show: Show[Blake2bHash28] = Show[ByteString].contramap(_.bytes)
    val BytesLength                        = 28

    def fromHex(hexString: String): Either[String, Blake2bHash28] =
      Blake2bHash.fromHex(hexString, BytesLength).map(Blake2bHash28.apply)

    def hash(bytes: ByteString): Blake2bHash28 = Blake2bHash28(Blake2bHash.hash(bytes, BytesLength))
  }

  @newtype final class Blake2bHash32(val bytes: ByteString)

  private object Blake2bHash {
    def fromHex(hexString: String, byteLength: Int): Either[String, ByteString] =
      Either
        .fromTry(Try(Hex.decodeAsArrayUnsafe(hexString)))
        .left
        .map(_.getMessage)
        .flatMap { bytes =>
          Either.cond(
            bytes.length == byteLength,
            ByteString(bytes),
            s"Invalid hash length for $hexString (should be $byteLength)."
          )
        }

    def hash(bytes: ByteString, byteLength: Int): ByteString = {
      val digestLengthInBytes = byteLength
      val blake2bDigest       = new Blake2bDigest(8 * digestLengthInBytes)
      val byteArray           = bytes.toArray
      blake2bDigest.update(byteArray, 0, byteArray.length)
      val out = Array.ofDim[Byte](blake2bDigest.getDigestSize)
      blake2bDigest.doFinal(out, 0)
      ByteString(out)
    }
  }

  implicit val showEddsaPublicKey: Show[EdDSA.PublicKey] = Show.fromToString
  implicit val showEddsaSignature: Show[EdDSA.Signature] = Show.fromToString

  @newtype final case class MainchainTxHash(value: ByteString)
  object MainchainTxHash {
    implicit val show: Show[MainchainTxHash] = Show.show[MainchainTxHash](a => show"MainchainTxHash(${a.value})")

    def decodeUnsafe(hex: String): MainchainTxHash = MainchainTxHash(Hex.decodeUnsafe(hex))

    def decode(hex: String): Either[String, MainchainTxHash] = Hex.decode(hex).map(MainchainTxHash.apply)
  }

  final case class UtxoId(txHash: MainchainTxHash, index: Int) {
    override def toString: String = ByteStringUtils.hash2string(txHash.value) + '#' + index
  }
  object UtxoId {
    implicit val show: Show[UtxoId] = Show.fromToString

    def parse(string: String): Either[String, UtxoId] = {
      val UtxoMatcher = raw"(\w{64})#(\d+)".r
      string match {
        case UtxoMatcher(txHash, index) =>
          for {
            txHash <- MainchainTxHash.decode(txHash)
            txId   <- Try(Integer.parseInt(index)).toEither.left.map(_.getMessage)
          } yield UtxoId(txHash, txId)
        case other => Left(s"$other is not a valid utxo id")
      }
    }

    def parseUnsafe(string: String): UtxoId = parse(string).left.map(e => throw new IllegalArgumentException(e)).merge
  }

  final case class MainchainTxOutput(
      id: UtxoId,
      blockNumber: MainchainBlockNumber,
      slotNumber: MainchainSlot,
      epochNumber: MainchainEpoch,
      txIndexInBlock: Int,
      address: MainchainAddress,
      datum: Option[Datum],
      spentBy: Option[SpentByInfo],
      txInputs: List[UtxoId]
  )

  final case class NftTxOutput(
      id: UtxoId,
      epoch: MainchainEpoch,
      blockNumber: MainchainBlockNumber,
      slot: MainchainSlot,
      txIndexWithinBlock: Int,
      datum: Option[Datum]
  )

  object NftTxOutput {
    implicit val show: Show[NftTxOutput] = cats.derived.semiauto.show
  }

  final case class MintAction(
      txHash: MainchainTxHash,
      assetName: AssetName,
      blockNumber: MainchainBlockNumber,
      txIndexInBlock: Int,
      redeemer: Option[Datum]
  )
  object MintAction {
    implicit val show: Show[MintAction] = cats.derived.semiauto.show
  }

  final case class SpentByInfo(
      transactionHash: MainchainTxHash,
      epoch: MainchainEpoch,
      slot: MainchainSlot
  )

  final case class MainchainBlockInfo(
      number: MainchainBlockNumber,
      hash: BlockHash,
      epoch: MainchainEpoch,
      slot: MainchainSlot,
      instant: Instant
  )

  @newtype final case class MainchainAddress(value: String)
  object MainchainAddress {
    implicit val show: Show[MainchainAddress] = deriving
  }

  /** Lovelace is the smallest unit of ADA, equivalent to one millionth of one token.
    * A Lovelace is to ADA what a Satoshi is to Bitcoin. 1 ADA = 1,000,000 Lovelace
    */
  @newtype final case class Lovelace(value: Long)
  object Lovelace {
    implicit val numeric: Numeric[Lovelace] = Numeric[Long].imap(Lovelace.apply)(_.value)
    implicit val show: Show[Lovelace]       = deriving

    def ofAda(value: Long): Lovelace = {
      val lovelaceInAda = 1000000
      Lovelace(value * lovelaceInAda)
    }
  }

  @newtype final case class PolicyId(value: ByteString)
  object PolicyId {
    implicit val show: Show[PolicyId] = deriving

    def empty: PolicyId = PolicyId(ByteString.empty)

    def fromHexUnsafe(hexString: String): PolicyId =
      PolicyId(Hex.decodeUnsafe(hexString))
  }

  /** Name of an asset. I.e. FUEL */
  @newtype final case class AssetName(value: ByteString)
  object AssetName {
    val MaxBytesLength                 = 32
    implicit val show: Show[AssetName] = deriving

    def empty: AssetName = AssetName(ByteString.empty)

    def fromHexUnsafe(hexString: String): AssetName =
      AssetName(Hex.decodeUnsafe(hexString))

    def fromHex(hexString: String): Either[String, AssetName] =
      Either
        .fromTry(Try(fromHexUnsafe(hexString)))
        .leftMap(_.getMessage)
        .flatMap { assetName =>
          Either.cond(
            assetName.value.length <= MaxBytesLength,
            assetName,
            s"Invalid hash length for $hexString (should be at most $MaxBytesLength)."
          )
        }
  }

  final case class Asset(policy: PolicyId, name: AssetName)

  object Asset {
    def fromHexUnsafe(policyHex: String, assetNameHex: String): Asset =
      Asset(PolicyId.fromHexUnsafe(policyHex), AssetName.fromHexUnsafe(assetNameHex))

    implicit val show: Show[Asset] = cats.derived.semiauto.show
  }

  object Blake2bHash32 {
    val BytesLength = 32

    def applyUnsafe(bytes: ByteString): Blake2bHash32 = apply(bytes) match {
      case Left(error)  => throw new IllegalArgumentException(error)
      case Right(value) => value
    }

    def apply(bytes: ByteString): Either[String, Blake2bHash32] =
      Either.cond(bytes.length == BytesLength, bytes.coerce, "Invalid bytes length for blake2bHash32")

    implicit val show: Show[Blake2bHash32] = Blake2bHash32.deriving
    implicit val eqv: Eq[Blake2bHash32]    = Blake2bHash32.deriving

    def fromHexUnsafe(hexString: String): Blake2bHash32 = fromHex(hexString) match {
      case Left(error)  => throw new IllegalArgumentException(error)
      case Right(value) => value
    }

    def fromHex(hexString: String): Either[String, Blake2bHash32] =
      Blake2bHash.fromHex(hexString, BytesLength).map(_.coerce)

    def hash(bytes: ByteString): Blake2bHash32 = Blake2bHash.hash(bytes, BytesLength).coerce
  }

  final case class MerkleRootNft(
      merkleRootHash: ByteString,
      txHash: MainchainTxHash,
      blockNumber: MainchainBlockNumber,
      previousMerkleRootHash: Option[ByteString]
  )
  final case class CommitteeNft(utxoInfo: UtxoInfo, committeeHash: Blake2bHash32, sidechainEpoch: SidechainEpoch)

  final case class CheckpointNft(utxoInfo: UtxoInfo, sidechainBlockHash: BlockHash, sidechainBlockNumber: BlockNumber)

  @newtype final case class EcdsaCompressedPubKey(bytes: ByteString)

  final case class UtxoInfo(
      utxoId: UtxoId,
      epoch: MainchainEpoch,
      blockNumber: MainchainBlockNumber,
      slotNumber: MainchainSlot,
      txIndexWithinBlock: Int
  )

  object UtxoInfo {
    implicit val show: Show[UtxoInfo] = cats.derived.semiauto.show
  }
}
