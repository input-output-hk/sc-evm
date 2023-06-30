package io.iohk.ethereum.crypto

import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.{
  Ed25519KeyGenerationParameters,
  Ed25519PrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.Ed25519Signer

import java.security.SecureRandom
import scala.util.Try

object EdDSA {
  def generateKeyPair(random: SecureRandom): (PrivateKey, PublicKey) = {
    val kpg = new Ed25519KeyPairGenerator
    kpg.init(new Ed25519KeyGenerationParameters(random))
    val kp              = kpg.generateKeyPair
    val eddsaPrivateKey = kp.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters]
    val eddsaPublicKey  = kp.getPublic.asInstanceOf[Ed25519PublicKeyParameters]
    (PrivateKey.fromEcParameters(eddsaPrivateKey), PublicKey.fromEcParams(eddsaPublicKey))
  }

  final class PublicKey private (val bytes: ByteString) {

    /** Once key is created this method is guaranteed not to throw an exception
      */
    def key: Ed25519PublicKeyParameters = new Ed25519PublicKeyParameters(bytes.toArray, 0)
    override def equals(obj: Any): Boolean =
      obj match {
        case other: PublicKey => bytes == other.bytes
        case _                => false
      }

    override def hashCode(): Int =
      bytes.hashCode() + 1

    override def toString: String = s"EdDSA.PublicKey(${Hex.toHexString(bytes.toArray)})"
  }

  object PublicKey {
    val KeyLength: Int  = 32
    val Zero: PublicKey = PublicKey.fromPrivate(PrivateKey.Zero)

    def fromPrivate(privateKey: PrivateKey): PublicKey =
      PublicKey.fromEcParams(privateKey.key.generatePublicKey())

    def fromEcParams(parameters: Ed25519PublicKeyParameters): PublicKey =
      new PublicKey(ByteString(parameters.getEncoded))

    def fromBytes(bytes: ByteString): Either[String, PublicKey] =
      Either
        .cond(bytes.length == KeyLength, bytes, s"Invalid key length. Expected $KeyLength but got ${bytes.length}")
        .flatMap { _ =>
          val key = new PublicKey(bytes)
          // force the construction of EC point to make sure that it won't throw later
          Try(key.key).toEither.left.map(_.getMessage).as(key)
        }

    def fromBytesUnsafe(bytes: ByteString): PublicKey =
      fromBytes(bytes) match {
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"Couldn't create eddsa public key from given bytes, reason: $reason, hex: ${Hex.toHexString(bytes.toArray)}"
          )
        case Right(value) => value
      }

    def fromHexUnsafe(hex: String): PublicKey =
      fromHex(hex) match {
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"Couldn't create eddsa public key from given bytes, reason: $reason, hex: $hex"
          )
        case Right(value) => value
      }

    def fromHex(hex: String): Either[String, PublicKey] =
      Hex.decode(hex).flatMap(fromBytes)
  }

  final class PrivateKey private (val bytes: ByteString) {

    /** Once key is created this method is guaranteed not to throw an exception
      */
    def key: Ed25519PrivateKeyParameters = new Ed25519PrivateKeyParameters(bytes.toArray, 0)

    def sign(message: ByteString): Signature = {
      val signer = new Ed25519Signer
      signer.init(true, key)
      signer.update(message.toArray, 0, message.length)
      new Signature(ByteString(signer.generateSignature()))
    }

    override def equals(obj: Any): Boolean =
      obj match {
        case other: PrivateKey => other.bytes == bytes
        case _                 => false
      }

    override def hashCode(): Int =
      bytes.hashCode() + 1

    override def toString: String = s"EdDSA.PrivateKey(***)"
  }

  object PrivateKey {
    val KeyLength: Int   = 32
    val Zero: PrivateKey = PrivateKey.fromEcParameters(new Ed25519PrivateKeyParameters(Array.ofDim(KeyLength), 0))

    def fromEcParameters(parameters: Ed25519PrivateKeyParameters): PrivateKey =
      new PrivateKey(ByteString(parameters.getEncoded))

    def fromBytes(bytes: ByteString): Either[String, PrivateKey] =
      Either
        .cond(bytes.length == KeyLength, bytes, s"Invalid key length. Expected $KeyLength but got ${bytes.length}")
        .flatMap { _ =>
          val key = new PrivateKey(bytes)
          // force the construction of EC point to make sure that it won't throw later
          Try(key.key).toEither.left.map(_.getMessage).as(key)
        }

    def fromBytesUnsafe(bytes: ByteString): PrivateKey =
      PrivateKey.fromBytes(bytes) match {
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"Couldn't create eddsa private key from given bytes, reason: $reason"
          )
        case Right(value) => value
      }

    def fromHex(hex: String): Either[String, PrivateKey] =
      Hex
        .decode(hex)
        .flatMap(PrivateKey.fromBytes)

    def fromHexUnsafe(hexString: String): PrivateKey = fromHex(hexString) match {
      case Left(value) =>
        throw new IllegalArgumentException(
          s"Couldn't create eddsa private key from given hex string, reason: $value"
        )
      case Right(value) => value
    }
  }

  final class Signature private[crypto] (val bytes: ByteString) {
    def verify(msg: ByteString, pubKey: PublicKey): Boolean = {
      val verifier = new Ed25519Signer
      verifier.init(false, pubKey.key)
      verifier.update(msg.toArray, 0, msg.length)
      verifier.verifySignature(bytes.toArray)
    }

    override def equals(obj: Any): Boolean = obj match {
      case s: Signature => bytes.equals(s.bytes)
      case _            => false
    }

    override def hashCode(): Int = bytes.hashCode() + 1

    override def toString: String = s"EdDSA.Signature(${Hex.toHexString(bytes.toArray)})"
  }

  object Signature {
    val SignatureLength = 64

    def fromBytes(bytes: ByteString): Either[String, Signature] =
      if (bytes.length == SignatureLength) {
        Right(new Signature(bytes))
      } else {
        Left(s"Invalid signature length. Expected length $SignatureLength but got ${bytes.length}")
      }

    def fromBytesUnsafe(bytes: ByteString): Signature =
      fromBytes(bytes) match {
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"Couldn't create eddsa private key from given bytes, reason: $reason, hex: ${Hex.toHexString(bytes.toArray)}"
          )
        case Right(value) => value
      }

    def fromHex(hex: String): Either[String, Signature] = {
      val bytes = Try(Hex.decodeAsArrayUnsafe(hex)).toEither.left.map(_.getMessage)
      bytes.flatMap(b => fromBytes(ByteString(b)))
    }

    def fromHexUnsafe(hex: String): Signature =
      fromHex(hex) match {
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"Couldn't create eddsa signature from given bytes, reason: $reason, hex: $hex"
          )
        case Right(value) => value
      }
  }
}
