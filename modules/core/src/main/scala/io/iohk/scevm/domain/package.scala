package io.iohk.scevm

import cats.Show
import cats.syntax.all._
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.ethereum.rlp.{RLPDecoder, RLPEncodeable, RLPEncoder}
import io.iohk.ethereum.utils.{ByteStringUtils, Hex}
import io.iohk.scevm.serialization.Newtype
import io.iohk.scevm.utils.moveme.ECPublicKeyParametersNodeId
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters

import scala.util.Try

package object domain {
  type SidechainPrivateKey = ECDSA.PrivateKey
  val SidechainPrivateKey = ECDSA.PrivateKey

  type SidechainPublicKey = ECDSA.PublicKey
  val SidechainPublicKey = ECDSA.PublicKey

  type SidechainSignature = ECDSASignature
  val SidechainSignature = ECDSASignature

  type SidechainSignatureNoRecovery = ECDSASignatureNoRecovery
  val SidechainSignatureNoRecovery = ECDSASignatureNoRecovery

  type MainchainPublicKey = EdDSA.PublicKey

  type MainchainSignature = EdDSA.Signature
  val MainchainSignature = EdDSA.Signature

  import scala.language.implicitConversions

  implicit def valueClassEnc[Wrapper, Underlying](implicit
      valueClass: Newtype[Wrapper, Underlying],
      encoder: RLPEncoder[Underlying]
  ): RLPEncoder[Wrapper] =
    (obj: Wrapper) => encoder.encode(valueClass.unwrap(obj))

  implicit def valueClassDec[Wrapper, Underlying](implicit
      valueClass: Newtype[Wrapper, Underlying],
      decoder: RLPDecoder[Underlying]
  ): RLPDecoder[Wrapper] =
    (rlp: RLPEncodeable) => valueClass.wrap(decoder.decode(rlp))

  implicit val show: Show[ECDSASignature] = Show.fromToString

  @newtype final case class NodeId(byteString: ByteString) {
    def toHex: String = ByteStringUtils.hash2string(byteString)
  }

  object NodeId {
    def fromStringUnsafe(str: String): NodeId = NodeId(Hex.decodeUnsafe(str))

    def fromString(str: String): Try[NodeId] = Try(NodeId(Hex.decodeUnsafe(str)))

    def fromKey(key: AsymmetricCipherKeyPair): NodeId = NodeId(
      ByteString(key.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId)
    )

    implicit val show: Show[NodeId] = deriving
  }

  @newtype final case class Gas(value: BigInt)

  object Gas {
    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifestGas: Manifest[Gas] = new Manifest[Gas] {
      override def runtimeClass: Class[_] = Gas.getClass
    }
  }

  /** Represents native side-chain currency.
    * The name is abstract since it will be something else on every side-chain.
    * It holds the smallest denomination of a given currency. You can think of it as Wei for Ethereum, or Satoshi for Bitcoin.
    */
  @newtype final case class Token(value: BigInt)

  object Token {
    implicit val show: Show[Token] = deriving

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifestToken: Manifest[Token] = new Manifest[Token] {
      override def runtimeClass: Class[_] = Token.getClass
    }
  }

  @newtype final case class BlockNumber(value: BigInt) {
    def +(distance: BlocksDistance): BlockNumber = BlockNumber(value + distance.value)

    def distance(other: BlockNumber): BlocksDistance = BlocksDistance(value - other.value)

    def -(distance: BlocksDistance): BlockNumber = BlockNumber(value - distance.value)

    def next: BlockNumber = BlockNumber(value + 1)

    def previous: BlockNumber = BlockNumber(value - 1)

    def toUInt256: UInt256 = UInt256(value)

    def toLong: Long = value.toLong
  }

  object BlockNumber {
    implicit val show: Show[BlockNumber]         = Show.show(b => show"BlockNumber(${b.value})")
    implicit val ordering: Ordering[BlockNumber] = Ordering.by(_.value)

    implicit def ordered(bn: BlockNumber): Ordered[BlockNumber] = Ordered.orderingToOrdered(bn)(ordering)

    implicit def blockNumberNumeric: Numeric[BlockNumber] = Numeric[BigInt].imap(BlockNumber(_))(_.value)

    implicit val valueClass: Newtype[BlockNumber, BigInt] =
      Newtype[BlockNumber, BigInt](BlockNumber.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[BlockNumber] = new Manifest[BlockNumber] {
      override def runtimeClass: Class[_] = BlockNumber.getClass
    }
  }

  @newtype final case class BlocksDistance(value: BigInt) {}

  object BlocksDistance {
    implicit val show: Show[BlocksDistance]         = deriving
    implicit val ordering: Ordering[BlocksDistance] = Ordering.by(_.value)

    implicit def ordered(bn: BlocksDistance): Ordered[BlocksDistance] = Ordered.orderingToOrdered(bn)(ordering)
  }

  @newtype final case class TransactionIndex(value: BigInt)

  object TransactionIndex {
    implicit val show: Show[TransactionIndex] = Show.show(t => show"TransactionIndex(${t.value})")

    implicit val valueClass: Newtype[TransactionIndex, BigInt] =
      Newtype[TransactionIndex, BigInt](TransactionIndex.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[TransactionIndex] = new Manifest[TransactionIndex] {
      override def runtimeClass: Class[_] = TransactionIndex.getClass
    }
  }

  implicit val valueClass: Newtype[SidechainPublicKey, ByteString] =
    Newtype.applyUnsafe[SidechainPublicKey, ByteString](SidechainPublicKey.fromBytes, _.bytes)

  implicit val orderingECDSAPublicKey: Ordering[ECDSA.PublicKey] = ByteStringUtils.byteStringOrdering.contramap(_.bytes)

  implicit val showForByteString: Show[ByteString] = io.iohk.bytes.showForByteString

  @newtype final case class EvmCode(bytes: ByteString)

  object EvmCode {
    implicit val show: Show[EvmCode] = Show.show(code => Hex.toHexString(code.bytes))
  }
}
