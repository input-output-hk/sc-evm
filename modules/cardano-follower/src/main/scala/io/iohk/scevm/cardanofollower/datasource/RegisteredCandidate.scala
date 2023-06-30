package io.iohk.scevm.cardanofollower.datasource

import cats.Show
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, EdDSA}
import io.iohk.scevm.domain.{SidechainPublicKey, SidechainSignatureNoRecovery}
import io.iohk.scevm.trustlesssidechain.cardano._

final case class RegisteredCandidate[CrossChainScheme <: AbstractSignatureScheme](
    mainchainPubKey: EdDSA.PublicKey,
    sidechainPubKey: SidechainPublicKey,
    crossChainPubKey: CrossChainScheme#PublicKey,
    sidechainSignature: SidechainSignatureNoRecovery,
    mainchainSignature: EdDSA.Signature,
    crossChainSignature: CrossChainScheme#SignatureWithoutRecovery,
    consumedInput: UtxoId,
    txInputs: List[UtxoId],
    utxoInfo: UtxoInfo,
    isPending: Boolean,
    pendingChange: Option[PendingRegistrationChange]
)

object RegisteredCandidate {
  implicit def show[CrossChainScheme <: AbstractSignatureScheme]: Show[RegisteredCandidate[CrossChainScheme]] =
    cats.derived.semiauto.show
}
