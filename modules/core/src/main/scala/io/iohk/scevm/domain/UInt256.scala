package io.iohk.scevm.domain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.{ByteUtils, Hex}
import io.iohk.scevm.domain.BigIntUtils._

import scala.language.implicitConversions

// scalastyle:off number.of.methods
object UInt256 {

  /** Size of UInt256 byte representation */
  val Size: Int = 32

  private val Modulus: BigInt = BigInt(2).pow(256)

  val MaxValue: UInt256 = new UInt256(Modulus - 1)

  val Zero: UInt256 = new UInt256(0)

  val One: UInt256 = new UInt256(1)

  val Two: UInt256 = new UInt256(2)

  def apply(bytes: ByteString): UInt256 = {
    require(
      bytes.length <= Size,
      s"Input byte array cannot be longer than $Size: ${bytes.length}"
    )
    UInt256(ByteUtils.toBigInt(bytes))
  }

  def apply(array: Array[Byte]): UInt256 =
    UInt256(ByteString(array))

  def apply(n: BigInt): UInt256 =
    new UInt256(boundBigInt(n))

  def apply(b: Boolean): UInt256 =
    if (b) One else Zero

  def apply(n: Long): UInt256 =
    apply(BigInt(n))

  implicit class BigIntAsUInt256(val bigInt: BigInt) extends AnyVal {
    def toUInt256: UInt256 = UInt256(bigInt)
  }

  implicit def uint256ToBigInt(uint: UInt256): BigInt = uint.toBigInt

  implicit def byte2UInt256(b: Byte): UInt256 = UInt256(b)

  implicit def int2UInt256(i: Int): UInt256 = UInt256(i)

  implicit def long2UInt256(l: Long): UInt256 = UInt256(l)

  implicit def bool2UInt256(b: Boolean): UInt256 = UInt256(b)

  private def boundBigInt(n: BigInt): BigInt = (n % Modulus + Modulus) % Modulus

  private val MaxSignedValue: BigInt = BigInt(2).pow(Size * 8 - 1) - 1

  object UInt256RLPImplicits {
    import io.iohk.ethereum.rlp._
    import io.iohk.ethereum.rlp.RLPSerializable
    import io.iohk.ethereum.rlp.RLPEncodeable
    import io.iohk.ethereum.rlp.RLP._

    implicit class UInt256Enc(obj: UInt256) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable =
        RLPValue(
          if (obj.equals(UInt256.Zero)) byteToByteArray(0: Byte) else obj.bytes.dropWhile(_ == 0).toArray[Byte]
        )
    }

    implicit class UInt256Dec(val bytes: ByteString) extends AnyVal {
      def toUInt256: UInt256 = UInt256RLPEncodableDec(rawDecode(bytes.toArray)).toUInt256
    }

    implicit class UInt256RLPEncodableDec(val rLPEncodeable: RLPEncodeable) extends AnyVal {
      def toUInt256: UInt256 = rLPEncodeable match {
        case RLPValue(b) => UInt256(b)
        case _           => throw RLPException("src is not an RLPValue")
      }
    }
  }
}

/** Represents 256 bit unsigned integers with standard arithmetic, byte-wise operation and EVM-specific extensions */
class UInt256 private (private val n: BigInt) extends Ordered[UInt256] {

  import UInt256._
  require(n >= 0 && n < Modulus, s"Invalid UInt256 value: $n")

  // byte-wise operations

  def bytes: ByteString = n.bytes(Size)

  /** Used for gas calculation for EXP opcode. See YP Appendix H.1 (220)
    * For n > 0: (n.bitLength - 1) / 8 + 1 == 1 + floor(log_256(n))
    *
    * @return Size in bytes excluding the leading 0 bytes
    */
  def byteSize: Int = if (isZero) 0 else (n.bitLength - 1) / 8 + 1

  def getByte(that: UInt256): UInt256 = if (that.n > Size - 1) Zero else UInt256(bytes(that.n.toInt) & 0xff)

  // standard arithmetic (note the use of new instead of apply where result is guaranteed to be within bounds)
  def &(that: UInt256): UInt256 = new UInt256(this.n & that.n)

  def |(that: UInt256): UInt256 = new UInt256(this.n | that.n)

  def ^(that: UInt256): UInt256 = new UInt256(this.n ^ that.n)

  def unary_- : UInt256 = UInt256(-n)

  def unary_~ : UInt256 = UInt256(~n)

  def +(that: UInt256): UInt256 = UInt256(this.n + that.n)

  def -(that: UInt256): UInt256 = UInt256(this.n - that.n)

  def *(that: UInt256): UInt256 = UInt256(this.n * that.n)

  def /(that: UInt256): UInt256 = new UInt256(this.n / that.n)

  def **(that: UInt256): UInt256 = UInt256(this.n.modPow(that.n, Modulus))

  def compare(that: UInt256): Int = this.n.compare(that.n)

  def min(that: UInt256): UInt256 = if (compare(that) < 0) this else that

  def max(that: UInt256): UInt256 = if (compare(that) > 0) this else that

  def isZero: Boolean = n == 0

  def <<(that: UInt256): UInt256 = UInt256(this.n.<<(that.toInt))

  def >>(that: UInt256): UInt256 = UInt256(this.n.>>(that.toInt))

  // EVM-specific arithmetic
  private lazy val signedN: BigInt = if (n > MaxSignedValue) n - Modulus else n

  private def zeroCheck(x: UInt256)(result: => BigInt): UInt256 =
    if (x.isZero) Zero else UInt256(result)

  def div(that: UInt256): UInt256 = zeroCheck(that)(new UInt256(this.n / that.n))

  def sdiv(that: UInt256): UInt256 = zeroCheck(that)(UInt256(this.signedN / that.signedN))

  def mod(that: UInt256): UInt256 = zeroCheck(that)(UInt256(this.n.mod(that.n)))

  def smod(that: UInt256): UInt256 = zeroCheck(that)(UInt256(this.signedN % that.signedN.abs))

  def addmod(that: UInt256, modulus: UInt256): UInt256 = zeroCheck(modulus) {
    new UInt256((this.n + that.n) % modulus.n)
  }

  def mulmod(that: UInt256, modulus: UInt256): UInt256 = zeroCheck(modulus) {
    new UInt256((this.n * that.n).mod(modulus.n))
  }

  def slt(that: UInt256): Boolean = this.signedN < that.signedN

  def sgt(that: UInt256): Boolean = this.signedN > that.signedN

  def sshift(that: UInt256): UInt256 = UInt256(this.signedN >> that.signedN.toInt)

  def signExtend(that: UInt256): UInt256 =
    if (that.n < 0 || that.n > 31) {
      this
    } else {
      val idx      = that.n.toByte
      val negative = n.testBit(idx * 8 + 7)
      val mask     = (BigInt(1) << ((idx + 1) * 8)) - 1
      val newN     = if (negative) n | (MaxValue ^ mask) else n & mask
      new UInt256(newN)
    }

  def fillingAdd(that: UInt256): UInt256 = {
    val result = this.n + that.n
    if (result > MaxValue)
      MaxValue
    else
      new UInt256(result)
  }

  //standard methods
  override def equals(that: Any): Boolean =
    that match {
      case that: UInt256 => this.n.equals(that.n)
      case other         => other == n
    }

  override def hashCode: Int = n.hashCode()

  override def toString: String = toSignedDecString

  def toDecString: String =
    n.toString

  def toSignedDecString: String =
    signedN.toString

  def toHexString: String = Hex.toHexString(n.toByteArray)

  def toSign: BigInt = signedN

  // conversions
  def toBigInt: BigInt = n

  /** @return an Int with MSB=0, thus a value in range [0, Int.MaxValue]
    */
  def toInt: Int = n.intValue & Int.MaxValue

  /** @return a Long with MSB=0, thus a value in range [0, Long.MaxValue]
    */
  def toLong: Long = n.longValue & Long.MaxValue
}
