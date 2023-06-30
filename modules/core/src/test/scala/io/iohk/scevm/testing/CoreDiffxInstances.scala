package io.iohk.scevm.testing

import com.softwaremill.diffx.Diff
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{BlockHash, Token}

object CoreDiffxInstances {
  implicit val diffForByteString: Diff[ByteString]                             = Diff.useEquals[String].contramap(bs => Hex.toHexString(bs))
  implicit val diffForEcdsaPubKey: Diff[ECDSA.PublicKey]                       = Diff[ByteString].contramap(_.bytes)
  implicit val diffForEcdsaSignature: Diff[ECDSASignature]                     = Diff[ByteString].contramap(_.toBytes)
  implicit val diffForEdDSAPubKey: Diff[EdDSA.PublicKey]                       = Diff[ByteString].contramap(_.bytes)
  implicit val diffForEdDSASignature: Diff[EdDSA.Signature]                    = Diff[ByteString].contramap(_.bytes)
  implicit val diffForEdDSASignatureNoRecovery: Diff[ECDSASignatureNoRecovery] = Diff[ByteString].contramap(_.toBytes)
  implicit val diffForBlockHash: Diff[BlockHash]                               = Diff.derived
  implicit val diffForToken: Diff[Token]                                       = Token.deriving
}
