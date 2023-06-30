package io.iohk.ethereum.crypto

import cats.Show
import cats.syntax.all._
import io.iohk.bytes.{ByteString, FromHex}
import io.iohk.ethereum.utils.{ByteUtils, Hex}
import org.bouncycastle.crypto.params.{ECPrivateKeyParameters, ECPublicKeyParameters}

import java.security.SecureRandom
import scala.util.Try

object ECDSA extends AbstractSignatureScheme {
  self =>
  type Signature                = ECDSASignature
  type SignatureWithoutRecovery = ECDSASignatureNoRecovery

  def generateKeyPair(secureRandom: SecureRandom): (PrivateKey, PublicKey) = {
    val keyPair = io.iohk.ethereum.crypto.generateKeyPair(secureRandom)
    val priv    = PrivateKey.fromEcParams(keyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters])
    val pub     = PublicKey.fromPrivateKey(priv)
    (priv, pub)
  }

  final class PrivateKey private (val bytes: ByteString) extends AbstractPrivateKey[ECDSASignature] {

    /** Once key is created this method is guaranteed not to throw an exception
      */
    def key: ECPrivateKeyParameters = {
      val ecPoint = BigInt(1, bytes.toArray)
      new ECPrivateKeyParameters(ecPoint.bigInteger, curve)
    }

    override def equals(obj: Any): Boolean =
      obj match {
        case other: PrivateKey => bytes == other.bytes
        case _                 => false
      }

    override def hashCode(): Int =
      bytes.hashCode() + 1

    override def toString: String = s"ECDSA.PrivateKey(***)"

    def sign(message: ByteString): ECDSASignature = ECDSASignature.sign(message, bytes)
  }

  object PrivateKey {
    implicit val ecdsaPrivateKeyFromHex: FromHex[PrivateKey] = fromHexUnsafe _
    val PrivateKeyLength: Int                                = 32
    val Zero: PrivateKey = {
      val magicNumber = 42
      PrivateKey.fromEcParams(new ECPrivateKeyParameters(BigInt(magicNumber).bigInteger, curve))
    }

    def fromEcParams(ecParameters: ECPrivateKeyParameters): PrivateKey =
      new PrivateKey(ByteString(ByteUtils.bigIntToBytes(ecParameters.getD, PrivateKeyLength)))

    def fromBytes(bytes: ByteString): Either[String, PrivateKey] =
      Either
        .cond(
          bytes.length == PrivateKeyLength,
          bytes,
          s"Invalid key length. Expected $PrivateKeyLength but got ${bytes.length}"
        )
        .flatMap { _ =>
          val key = new PrivateKey(bytes)
          // force the construction of EC point to make sure that it won't throw later
          Try(key.key).toEither.left.map(_.getMessage).as(key)
        }

    def fromHex(hexString: String): Either[String, PrivateKey] =
      Hex
        .decode(hexString)
        .flatMap(PrivateKey.fromBytes)

    def fromHexUnsafe(hexString: String): PrivateKey = fromHex(hexString) match {
      case Left(value) =>
        throw new IllegalArgumentException(
          s"Couldn't create ecdsa private key from given hex string, reason: $value"
        )
      case Right(value) => value
    }
  }

  final class PublicKey private (val bytes: ByteString) extends AbstractPublicKey {

    def toBytes: ByteString = bytes

    /** Once key is created this method is guaranteed not to throw an exception
      */
    def key: ECPublicKeyParameters = {
      val ecPoint = curve.getCurve.decodePoint(ECDSASignature.UncompressedIndicator +: bytes.toArray)
      new ECPublicKeyParameters(ecPoint, curve)
    }

    /** Returns the compressed 33 bytes including the even/odd indicator */
    def compressedBytes: ByteString = {
      val ecPoint = curve.getCurve.decodePoint(ECDSASignature.UncompressedIndicator +: bytes.toArray)
      ByteString(new ECPublicKeyParameters(ecPoint, curve).getQ.getEncoded(true))
    }

    override def equals(obj: Any): Boolean =
      obj match {
        case other: PublicKey => bytes == other.bytes
        case _                => false
      }

    override def hashCode(): Int =
      bytes.hashCode() + 1

    override def toString: String = s"ECDSA.PublicKey(${Hex.toHexString(bytes.toArray)})"
  }

  object PublicKey {
    val KeyLength: Int           = 64
    val CompressedKeyLength: Int = 33
    val Zero: PublicKey          = PublicKey.fromPrivateKey(PrivateKey.Zero)

    implicit val show: Show[PublicKey] = Show.fromToString

    def fromEcParams(ecParameters: ECPublicKeyParameters): PublicKey =
      new PublicKey(ByteString(ecParameters.getQ.getEncoded(false).tail))

    def fromBytes(bytes: ByteString): Either[String, PublicKey] =
      Either
        .cond(bytes.length == KeyLength, bytes, s"Invalid key length. Expected $KeyLength but got ${bytes.length}")
        .flatMap { _ =>
          val key = new PublicKey(bytes)
          // force the construction of EC point to make sure that it won't throw later
          Try(key.key).toEither.left.map(_.getMessage).as(key)
        }

    /** Parse a compressed key including the even/odd byte. */
    def fromCompressedBytes(bytes: ByteString): Either[String, PublicKey] =
      Either
        .cond(
          bytes.length == CompressedKeyLength,
          bytes,
          s"Invalid key length. Expected $CompressedKeyLength but got ${bytes.length}"
        )
        .flatMap { _ =>
          Try {
            val ecPoint = curve.getCurve.decodePoint(bytes.toArray)
            new ECPublicKeyParameters(ecPoint, curve)
          }.toEither
            .bimap(_.getMessage, PublicKey.fromEcParams)
        }

    def fromPrivateKey(privateKey: PrivateKey): PublicKey = {
      val ecPoint    = curve.getG.multiply(privateKey.key.getD).normalize()
      val parameters = new ECPublicKeyParameters(ecPoint, curve)
      PublicKey.fromEcParams(parameters)
    }

    def fromHex(hexString: String): Either[String, PublicKey] =
      Hex.decode(hexString).flatMap(PublicKey.fromBytes)

    def fromHexUnsafe(hexString: String): PublicKey = fromHex(hexString) match {
      case Left(reason) =>
        throw new IllegalArgumentException(
          s"Couldn't create ecdsa public key from given hex string, reason: $reason, hexString: $hexString"
        )
      case Right(value) => value
    }

    def fromBytesUnsafe(bytes: ByteString): PublicKey =
      ECDSA.PublicKey.fromBytes(bytes) match {
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"Couldn't create ecdsa public key from given bytes, reason: $reason, hex: ${Hex.toHexString(bytes.toArray)}"
          )
        case Right(value) => value
      }
  }
}
