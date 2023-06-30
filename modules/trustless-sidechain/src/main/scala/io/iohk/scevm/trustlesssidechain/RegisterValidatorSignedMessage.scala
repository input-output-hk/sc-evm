package io.iohk.scevm.trustlesssidechain

import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.plutus.DatumCodec
import io.iohk.scevm.trustlesssidechain.cardano._

// scalastyle:off line.size.limit
/** When a SPO registers, it should build this message and sign it with both its keys from the mainchain and sidechain.
  *
  * Note that the ECDSA secp256k1 public key is serialized in compressed format.
  *
  * original type: https://github.com/mlabs-haskell/trustless-sidechain/blob/6950e8c2c012bf4356e642b5f948ea042d0d7e6f/src/TrustlessSidechain/OnChain/CommitteeCandidateValidator.hs#L44
  * ```
  * data BlockProducerRegistrationMsg = BlockProducerRegistrationMsg
  *  { bprmSidechainParams :: !SidechainParams
  *  , bprmSidechainPubKey :: !BuiltinByteString
  *  , -- | A UTxO that must be spent by the transaction
  *    bprmInputUtxo :: !TxOutRef
  *  }
  *  ```
  */
final case class RegisterValidatorSignedMessage(
    sidechainParams: SidechainParams,
    sidechainPubKey: ECDSA.PublicKey,
    inputUtxo: UtxoId
)

object RegisterValidatorSignedMessage {
  implicit val registerValidatorSignedMessageDatumEncoder: DatumCodec[RegisterValidatorSignedMessage] =
    DatumCodec.derive
}
