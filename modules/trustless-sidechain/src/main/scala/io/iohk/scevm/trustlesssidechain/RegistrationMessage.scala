package io.iohk.scevm.trustlesssidechain

import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.SidechainPublicKey
import io.iohk.scevm.plutus.DatumEncoder
import io.iohk.scevm.trustlesssidechain.cardano.UtxoId

object RegistrationMessage {
  def createEncoded(
      sidechainPubKey: SidechainPublicKey,
      consumedUtxo: UtxoId,
      sidechainParams: SidechainParams
  ): ByteString = {
    val message = RegisterValidatorSignedMessage(sidechainParams, sidechainPubKey, consumedUtxo)
    ByteString(Cbor.encode(DatumEncoder[RegisterValidatorSignedMessage].encode(message)).toByteArray)
  }
}
