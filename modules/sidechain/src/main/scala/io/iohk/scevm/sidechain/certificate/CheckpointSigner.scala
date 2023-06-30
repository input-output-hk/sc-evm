package io.iohk.scevm.sidechain.certificate

import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.scevm.domain.{BlockHash, BlockNumber}

object CheckpointSigner {

  final case class SignedCheckpoint(hash: BlockHash, number: BlockNumber, signature: ECDSASignature)
}
