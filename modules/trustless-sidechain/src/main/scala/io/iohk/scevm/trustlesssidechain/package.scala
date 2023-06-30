package io.iohk.scevm

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.plutus.DatumCodec
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.trustlesssidechain.cardano._

package object trustlesssidechain {
  implicit val ecdsaPublicKeyDatumEncoder: DatumCodec[ECDSA.PublicKey] =
    DatumCodec[ByteString]
      .imapEither(bs => ECDSA.PublicKey.fromCompressedBytes(bs).left.map(DatumDecodeError(_)))(_.compressedBytes)
  implicit val mainchainTxHashEncoder: DatumCodec[MainchainTxHash]      = MainchainTxHash.deriving[DatumCodec].nest
  implicit val utxoIdDatumEncoder: DatumCodec[UtxoId]                   = DatumCodec.derive[UtxoId]
  implicit val chainId: DatumCodec[ChainId]                             = DatumCodec[Int].imap(i => ChainId.unsafeFrom(BigInt(i)))(_.value)
  implicit val blockHashEncoder: DatumCodec[BlockHash]                  = DatumCodec[ByteString].imap(BlockHash.apply)(_.byteString)
  implicit val blockNumberEncoder: DatumCodec[BlockNumber]              = DatumCodec[BigInt].imap(BlockNumber.apply)(_.value)
  implicit val sidechainParamsDatumEncoder: DatumCodec[SidechainParams] = DatumCodec.derive

  implicit val eddsaSignatureDecoder: DatumCodec[EdDSA.Signature] =
    DatumCodec[ByteString].imapEither(bs => EdDSA.Signature.fromBytes(bs).left.map(DatumDecodeError(_)))(_.bytes)
  implicit val ecdsaSignatureNoRecoveryDecoder: DatumCodec[ECDSASignatureNoRecovery] =
    DatumCodec[ByteString]
      .imapEither(bs => ECDSASignatureNoRecovery.fromBytes(bs).left.map(DatumDecodeError(_)))(_.toBytes)
  implicit val eddsaPublicKeyDecoder: DatumCodec[EdDSA.PublicKey] =
    DatumCodec[ByteString].imapEither(bs => EdDSA.PublicKey.fromBytes(bs).left.map(DatumDecodeError(_)))(_.bytes)
  implicit val ecdsaPublicKeyCompressedEncoder: DatumCodec[EcdsaCompressedPubKey] =
    EcdsaCompressedPubKey.deriving[DatumCodec]
  implicit val blake2bHash32: DatumCodec[Blake2bHash32] =
    DatumCodec[ByteString].imapEither(bs => Blake2bHash32(bs).left.map(DatumDecodeError.apply))(_.bytes)

}
