package io.iohk.ethereum.crypto

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex

trait AbstractSignatureScheme {
  type Signature <: AbstractSignature[PublicKey, SignatureWithoutRecovery]
  type SignatureWithoutRecovery <: AbstractSignature[PublicKey, SignatureWithoutRecovery]
  type PrivateKey <: AbstractPrivateKey[Signature]
  type PublicKey <: AbstractPublicKey
}

trait AbstractSignature[PublicKey <: AbstractPublicKey, NoRecovery <: AbstractSignature[_, _]] {
  def toBytes: ByteString

  /** Verify that msg has been signed by pubKey */
  def verify(msg: ByteString, pubKey: PublicKey): Boolean

  def withoutRecovery: NoRecovery
}

object AbstractSignature {
  implicit def show[Signature <: AbstractSignature[_, _]]: Show[Signature] = (signature: Signature) =>
    s"${signature.getClass.getName}(${Hex.toHexString(signature.toBytes)})"
}

trait AbstractPrivateKey[Signature <: AbstractSignature[_, _]] {
  def sign(bytes: ByteString): Signature
}

object AbstractPrivateKey {
  implicit def show[Signature <: AbstractSignature[_, _]]: Show[AbstractPrivateKey[Signature]] = _ => "***"
}

trait AbstractPublicKey {
  def toBytes: ByteString
}

object AbstractPublicKey {
  implicit def show[PubKey <: AbstractPublicKey]: Show[PubKey] = Show.fromToString
}
