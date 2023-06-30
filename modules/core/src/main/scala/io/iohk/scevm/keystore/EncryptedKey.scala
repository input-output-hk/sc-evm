package io.iohk.scevm.keystore

import cats._
import cats.effect.std.Random
import cats.implicits._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.SymmetricCipher
import io.iohk.scevm.domain.{Address, SidechainPrivateKey, SidechainPublicKey}
import io.iohk.scevm.keystore.EncryptedKey._

import java.util.UUID

object EncryptedKey {
  val AES128CTR = "aes-128-ctr"
  val AES128CBC = "aes-128-cbc"
  val Scrypt    = "scrypt"
  val Pbkdf2    = "pbkdf2"

  sealed trait KdfParams
  final case class ScryptParams(salt: ByteString, n: Int, r: Int, p: Int, dklen: Int) extends KdfParams
  final case class Pbkdf2Params(salt: ByteString, prf: String, c: Int, dklen: Int)    extends KdfParams

  final case class CryptoSpec(
      cipher: String,
      ciphertext: ByteString,
      iv: ByteString,
      kdfParams: KdfParams,
      mac: ByteString
  )

  def create[F[_]: Monad: Random](prvKey: SidechainPrivateKey, passphrase: String): F[EncryptedKey] = {
    val version = 3
    val uuid    = UUID.randomUUID()
    val pubKey  = SidechainPublicKey.fromPrivateKey(prvKey).bytes
    val address = Address(crypto.kec256(pubKey))

    for {
      salt     <- Random[F].nextBytes(32).map(ByteString(_))
      kdfParams = ScryptParams(salt, 1 << 18, 8, 1, 32) //params used by Geth
      dk        = deriveKey(passphrase, kdfParams)

      cipherName = AES128CTR
      iv        <- Random[F].nextBytes(16).map(ByteString(_))
      secret     = dk.take(16)
      ciphertext = getCipher(cipherName).encrypt(secret, iv, prvKey.bytes)

      mac = createMac(dk, ciphertext)

      cryptoSpec = CryptoSpec(cipherName, ciphertext, iv, kdfParams, mac)
    } yield EncryptedKey(uuid, address, cryptoSpec, version)
  }

  private def getCipher(cipherName: String): SymmetricCipher =
    Map(AES128CBC -> crypto.AES_CBC, AES128CTR -> crypto.AES_CTR)(cipherName.toLowerCase)

  private def deriveKey(passphrase: String, kdfParams: KdfParams): ByteString =
    kdfParams match {
      case ScryptParams(salt, n, r, p, dklen) =>
        crypto.scrypt(passphrase, salt, n, r, p, dklen)

      case Pbkdf2Params(salt, _, c, dklen) =>
        // prf is currently ignored, only hmac sha256 is used
        crypto.pbkdf2HMacSha256(passphrase, salt, c, dklen)
    }

  private def createMac(dk: ByteString, ciphertext: ByteString): ByteString =
    crypto.kec256(dk.slice(16, 32) ++ ciphertext)
}

/** Represents an encrypted private key stored in the keystore
  * See: https://github.com/ethereum/wiki/wiki/Web3-Secret-Storage-Definition
  */
final case class EncryptedKey(
    id: UUID,
    address: Address,
    cryptoSpec: CryptoSpec,
    version: Int
) {

  def decrypt(passphrase: String): Either[String, SidechainPrivateKey] = {
    val dk        = deriveKey(passphrase, cryptoSpec.kdfParams)
    val secret    = dk.take(16)
    val decrypted = getCipher(cryptoSpec.cipher).decrypt(secret, cryptoSpec.iv, cryptoSpec.ciphertext)
    decrypted
      .filter(_ => createMac(dk, cryptoSpec.ciphertext) == cryptoSpec.mac)
      .map(key => SidechainPrivateKey.fromBytes(key))
      .getOrElse(Left("Couldn't decrypt key with given passphrase"))
  }
}
