package io.iohk.ethereum.crypto

import cats.Show
import io.iohk.bytes.{ByteString, FromBytes}
import io.iohk.ethereum.utils.{ByteUtils, Hex}
import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECCurve, ECPoint}

import scala.util.Try

object ECDSASignature {

  val SLength            = 32
  val RLength            = 32
  val VLength            = 1
  val EncodedLength: Int = RLength + SLength + VLength

  //byte value that indicates that bytes representing ECC point are in uncompressed format, and should be decoded properly
  val UncompressedIndicator: Byte   = 0x04
  val CompressedEvenIndicator: Byte = 0x02
  val CompressedOddIndicator: Byte  = 0x03

  //only naming convention
  // Pre EIP155 signature.v convention
  val negativePointSign: Byte = 27
  val positivePointSign: Byte = 28
  // yParity
  val negativeYParity: Byte = 0
  val positiveYParity: Byte = 1

  val allowedPointSigns: Set[Byte] = Set(negativePointSign, positivePointSign)

  def apply(r: ByteString, s: ByteString, v: Byte): ECDSASignature =
    ECDSASignature(BigInt(1, r.toArray), BigInt(1, s.toArray), v)

  implicit def ecdsaSignatureFromBytes: FromBytes[ECDSASignature] = fromBytesUnsafe _

  def fromBytes(bytes65: ByteString): Either[String, ECDSASignature] =
    if (bytes65.length == EncodedLength)
      Right(apply(bytes65.take(RLength), bytes65.drop(RLength).take(SLength), bytes65.last))
    else if (bytes65.length == ECDSASignatureNoRecovery.EncodedLength)
      Left(
        s"Invalid signature length ${bytes65.length} (expected $EncodedLength). This may be an ECDSASignature without a recovery byte."
      )
    else
      Left(s"Invalid signature length. Expected $EncodedLength, but got ${bytes65.length}")

  def fromBytesUnsafe(bytes65: ByteString): ECDSASignature =
    ECDSASignature
      .fromBytes(bytes65)
      .getOrElse(
        throw new IllegalArgumentException(s"Couldn't create ecdsa signature from ${Hex.toHexString(bytes65)}")
      )

  def fromHex(hex: String): Either[String, ECDSASignature] =
    Hex.decode(hex).flatMap(fromBytes)

  def fromHexUnsafe(hex: String): ECDSASignature =
    fromHex(hex).getOrElse(
      throw new IllegalArgumentException(s"Couldn't create ecdsa signature from $hex")
    )

  def fromUnrecoverable(
      signature: ECDSASignatureNoRecovery,
      publicKey: ECDSA.PublicKey,
      messageHash: ByteString
  ): Option[ECDSASignature] = {
    val vOpt =
      ECDSASignature.calculateVFromPubKey(signature.r, signature.s, publicKey.bytes.toArray, messageHash.toArray)
    vOpt.map(ECDSASignature(signature.r, signature.s, _))
  }

  def sign(messageHash: ByteString, prvKey: ByteString): ECDSASignature =
    sign(messageHash.toArray, keyPairFromPrvKey(prvKey))

  /** Sign a messageHash, expected to be a Keccak256 hash of the original data. */
  def sign(messageHash: Array[Byte], keyPair: AsymmetricCipherKeyPair): ECDSASignature = {
    require(
      messageHash.length == 32,
      s"The message should be a hash, expected to be 32 bytes; got ${messageHash.length} bytes."
    )
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
    signer.init(true, keyPair.getPrivate)
    val components = signer.generateSignature(messageHash)
    val r          = components(0)
    val s          = ECDSASignature.canonicalise(components(1))
    val v = ECDSASignature
      .calculateV(r, s, keyPair, messageHash)
      .getOrElse(throw new RuntimeException("Failed to calculate signature rec id"))

    ECDSASignature(r, s, v)
  }

  /** Check that a pointSign is within the allowed values {27, 28}
    *
    * @param pointSign
    * @return
    */
  private def checkPointSignValidity(pointSign: Byte): Option[Byte] =
    Some(pointSign).filter(pointSign => allowedPointSigns.contains(pointSign))

  private def canonicalise(s: BigInt): BigInt = {
    val halfCurveOrder: BigInt = curveParams.getN.shiftRight(1)
    if (s > halfCurveOrder) BigInt(curve.getN) - s
    else s
  }

  private def calculateV(r: BigInt, s: BigInt, key: AsymmetricCipherKeyPair, messageHash: Array[Byte]): Option[Byte] = {
    //byte 0 of encoded ECC point indicates that it is uncompressed point, it is part of bouncycastle encoding
    val pubKey = key.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail
    calculateVFromPubKey(r, s, pubKey, messageHash)
  }

  private def calculateVFromPubKey(
      r: BigInt,
      s: BigInt,
      pubKey: Array[Byte],
      messageHash: Array[Byte]
  ): Option[Byte] = {
    val recIdOpt = Seq(positivePointSign, negativePointSign).find { i =>
      recoverPubBytes(r, s, i, messageHash).exists(java.util.Arrays.equals(_, pubKey))
    }
    recIdOpt
  }

  private def recoverPubBytes(
      r: BigInt,
      s: BigInt,
      recId: Byte,
      messageHash: Array[Byte]
  ): Option[Array[Byte]] =
    Try {
      val order = curve.getCurve.getOrder
      //ignore case when x = r + order because it is negligibly improbable
      //says: https://github.com/paritytech/rust-secp256k1/blob/f998f9a8c18227af200f0f7fdadf8a6560d391ff/depend/secp256k1/src/ecdsa_impl.h#L282
      val xCoordinate = r
      val curveFp     = curve.getCurve.asInstanceOf[ECCurve.Fp]
      val prime       = curveFp.getQ

      checkPointSignValidity(recId).flatMap { recovery =>
        if (xCoordinate.compareTo(prime) < 0) {
          val R = constructPoint(xCoordinate, recovery)
          if (R.multiply(order).isInfinity) {
            val e    = BigInt(1, messageHash)
            val rInv = r.modInverse(order)
            //Q = r^(-1)(sR - eG)
            val q = R.multiply(s.bigInteger).subtract(curve.getG.multiply(e.bigInteger)).multiply(rInv.bigInteger)
            //byte 0 of encoded ECC point indicates that it is uncompressed point, it is part of bouncycastle encoding
            Some(q.getEncoded(false).tail)
          } else None
        } else None
      }
    }.toOption.flatten

  private def constructPoint(xCoordinate: BigInt, recId: Int): ECPoint = {
    val x9      = new X9IntegerConverter
    val compEnc = x9.integerToBytes(xCoordinate.bigInteger, 1 + x9.getByteLength(curve.getCurve))
    compEnc(0) = if (recId == ECDSASignature.positivePointSign) 3.toByte else 2.toByte
    curve.getCurve.decodePoint(compEnc)
  }
}

/** ECDSASignature r and s are same as in documentation where signature is represented by tuple (r, s)
  *
  * The `publicKey` method is also the way to verify the signature: if the key can be retrieved based
  * on the signed message, the signature is correct, otherwise it isn't.
  *
  * @param r - x coordinate of ephemeral public key modulo curve order N
  * @param s - part of the signature calculated with signer private key
  * @param v - public key recovery id
  */
final case class ECDSASignature(r: BigInt, s: BigInt, v: Byte)
    extends AbstractSignature[ECDSA.PublicKey, ECDSASignatureNoRecovery] {

  /** returns ECC point encoded with on compression and without leading byte indicating compression
    *
    * @param messageHash message to be signed; should be a hash of the actual data.
    * @param chainId     optional value if you want new signing schema with recovery id calculated with chain id
    * @return
    */
  def publicKey(messageHash: Array[Byte]): Option[Array[Byte]] =
    ECDSASignature.recoverPubBytes(r, s, v, messageHash)

  /** returns ECC point encoded with on compression and without leading byte indicating compression
    *
    * @param messageHash message to be signed; should be a hash of the actual data.
    * @return
    */
  def publicKey(messageHash: ByteString): Option[ByteString] =
    ECDSASignature.recoverPubBytes(r, s, v, messageHash.toArray[Byte]).map(ByteString(_))

  def verify(messageHash: ByteString, pubKey: ECDSA.PublicKey): Boolean =
    verify(messageHash, pubKey.toBytes)

  def verify(messageHash: ByteString, pubKey: ByteString): Boolean =
    publicKey(messageHash).contains(pubKey)

  def toBytes: ByteString = {
    import ECDSASignature.RLength

    def bigInt2Bytes(b: BigInt) =
      ByteUtils.padLeft(ByteString(b.toByteArray).takeRight(RLength), RLength, 0)

    bigInt2Bytes(r) ++ bigInt2Bytes(s) ++ ByteString(v)
  }

  def withoutRecoveryByte: ECDSASignatureNoRecovery = ECDSASignatureNoRecovery(r, s)

  def withoutRecovery: ECDSASignatureNoRecovery = withoutRecoveryByte
}

object ECDSASignatureNoRecovery {
  implicit val show: Show[ECDSASignatureNoRecovery] = Show.fromToString
  val EncodedLength: Int                            = ECDSASignature.EncodedLength - ECDSASignature.VLength

  def apply(r: ByteString, s: ByteString): ECDSASignatureNoRecovery =
    ECDSASignatureNoRecovery(BigInt(1, r.toArray), BigInt(1, s.toArray))

  def fromBytes(bytes64: ByteString): Either[String, ECDSASignatureNoRecovery] =
    if (bytes64.length == EncodedLength) {
      val (r, s) = bytes64.splitAt(ECDSASignature.RLength)
      Right(apply(r, s))
    } else if (bytes64.length == ECDSASignature.EncodedLength) {
      Left(
        s"Invalid signature length ${bytes64.length} (expected $EncodedLength). This may be an ECDSASignature with a recovery byte."
      )
    } else
      Left(s"Invalid signature length. Expected $EncodedLength, but got ${bytes64.length}")

  def fromHex(hex: String): Either[String, ECDSASignatureNoRecovery] = {
    val bytes = Try(Hex.decodeAsArrayUnsafe(hex)).toEither.left.map(_.getMessage)
    bytes.flatMap(b => fromBytes(ByteString(b)))
  }

  def fromHexUnsafe(hex: String): ECDSASignatureNoRecovery =
    fromHex(hex).getOrElse(
      throw new IllegalArgumentException(s"Couldn't create ecdsa signature without recovery byte from $hex")
    )
}

/** ECDSASignature r and s are same as in documentation where signature is represented by tuple (r, s)
  *
  * Contrary to [[ECDSASignature]], this class does not contain the eth specific fields v to recover
  * the public key.
  *
  * @param r - x coordinate of ephemeral public key modulo curve order N
  * @param s - part of the signature calculated with signer private key
  */
final case class ECDSASignatureNoRecovery(r: BigInt, s: BigInt)
    extends AbstractSignature[ECDSA.PublicKey, ECDSASignatureNoRecovery] {
  override def toString: String = s"ECDSASignatureNoRecovery(${Hex.toHexString(toBytes)})"

  def toBytes: ByteString = {
    import ECDSASignature.RLength

    def bigInt2Bytes(b: BigInt) =
      ByteUtils.padLeft(ByteString(b.toByteArray).takeRight(RLength), RLength, 0)

    bigInt2Bytes(r) ++ bigInt2Bytes(s)
  }

  /** Verify that msg has been signed by pubKey */
  override def verify(msg: ByteString, pubKey: ECDSA.PublicKey): Boolean =
    ECDSASignature
      .fromUnrecoverable(this, pubKey, msg)
      .exists(_.verify(msg, pubKey))

  def withoutRecovery: ECDSASignatureNoRecovery = this
}
