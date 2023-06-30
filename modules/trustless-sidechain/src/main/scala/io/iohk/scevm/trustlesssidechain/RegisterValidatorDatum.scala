package io.iohk.scevm.trustlesssidechain

import cats.syntax.all._
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, EdDSA}
import io.iohk.scevm.domain.{SidechainPublicKey, SidechainSignatureNoRecovery}
import io.iohk.scevm.plutus.DatumDecoder
import io.iohk.scevm.trustlesssidechain
import io.iohk.scevm.trustlesssidechain.cardano._

/** Representation of the plutus type in the mainchain contract:
  *
  * Note that the ECDSA secp256k1 public key is serialized in compressed format and the
  * sidechain signature does not contain the recovery bytes (it's just r an s concatenated).
  *
  * ```
  * data BlockProducerRegistration = BlockProducerRegistration
  *  { -- | SPO cold verification key hash
  *    bprSpoPubKey :: !PubKey -- own cold verification key hash
  *  , -- | public key in the sidechain's desired format
  *    bprSidechainPubKey :: !BuiltinByteString
  *  , -- | Signature of the SPO with the mainchain key
  *    bprSpoSignature :: !Signature
  *  , -- | Signature of the SPO with the sidechain key
  *    bprSidechainSignature :: !Signature
  *  , -- | A UTxO that must be spent by the transaction
  *    bprInputUtxo :: !TxOutRef
  *  }
  * ```
  */
final case class RegisterValidatorDatum[CrossChainScheme <: AbstractSignatureScheme](
    mainchainPubKey: EdDSA.PublicKey,
    sidechainPubKey: SidechainPublicKey,
    crossChainPubKey: CrossChainScheme#PublicKey,
    mainchainSignature: EdDSA.Signature,
    sidechainSignature: SidechainSignatureNoRecovery,
    crossChainSignature: CrossChainScheme#SignatureWithoutRecovery,
    consumedInput: UtxoId
)

object RegisterValidatorDatum {

  implicit def decoder[
      CrossChainScheme <: AbstractSignatureScheme
  ]: DatumDecoder[RegisterValidatorDatum[CrossChainScheme]] = DatumDecoder
    .derive[PhysicalSchema.RegisterValidatorDatum]
    .map(_.toRegisterValidatorDatum[CrossChainScheme])

  object PhysicalSchema {
    final case class RegisterValidatorDatum(
        mainchainPubKey: EdDSA.PublicKey,
        sidechainPubKey: SidechainPublicKey,
        mainchainSignature: EdDSA.Signature,
        sidechainSignature: SidechainSignatureNoRecovery,
        consumedInput: UtxoId
    ) {
      def toRegisterValidatorDatum[
          CrossChainScheme <: AbstractSignatureScheme
      ]: trustlesssidechain.RegisterValidatorDatum[CrossChainScheme] =
        trustlesssidechain.RegisterValidatorDatum(
          mainchainPubKey,
          sidechainPubKey,
          // TODO: remove the casts by fetching the key and signature from MC contract
          sidechainPubKey.asInstanceOf[CrossChainScheme#PublicKey],
          mainchainSignature,
          sidechainSignature,
          sidechainSignature.asInstanceOf[CrossChainScheme#SignatureWithoutRecovery],
          consumedInput
        )
    }
  }
}
