package io.iohk.scevm.trustlesssidechain

import cats.Show
import io.iohk.bytes.{ByteString, showForByteString}
import io.iohk.ethereum.crypto.ECDSASignatureNoRecovery
import io.iohk.scevm.plutus.DatumCodec
import io.iohk.scevm.trustlesssidechain.cardano.EcdsaCompressedPubKey
/*
data SignedMerkleRoot = SignedMerkleRoot
  { merkleRoot :: ByteString
  , previousMerkleRoot :: Maybe ByteString -- Last Merkle root hash
  , signatures :: [ByteString] -- Current committee signatures ordered as their corresponding keys
  , committeePubKeys :: [SidechainPubKey] -- Lexicographically sorted public keys of all committee members
  }
 */
final case class SignedMerkleRootRedeemer(
    merkleRoot: ByteString,
    previousMerkleRoot: Option[ByteString],
    signatures: List[ECDSASignatureNoRecovery],
    committeePubKeys: List[EcdsaCompressedPubKey]
)

object SignedMerkleRootRedeemer {
  implicit val signedMerkleRootDatumCodec: DatumCodec[SignedMerkleRootRedeemer] = DatumCodec.derive
  implicit val showForEcdsaCompressedPubKey: Show[EcdsaCompressedPubKey]        = EcdsaCompressedPubKey.deriving
  implicit val showForEcdsaSignatureNoRecovery: Show[ECDSASignatureNoRecovery]  = cats.derived.semiauto.show
  implicit val show: Show[SignedMerkleRootRedeemer]                             = cats.derived.semiauto.show
}
