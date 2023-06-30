package io.iohk.scevm.testing

import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA, ECDSASignature}
import io.iohk.scevm.domain.{SidechainPrivateKey, SidechainPublicKey}
import io.iohk.scevm.testing.Generators.{bigIntGen, byteGen}
import org.bouncycastle.asn1.x9.X9ECPoint
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.{ECPrivateKeyParameters, ECPublicKeyParameters}
import org.bouncycastle.util.encoders.Hex
import org.scalacheck.Gen

import java.math.BigInteger
import scala.io.Source
import scala.util.Random

object CryptoGenerators {

  private def asECDSA(keyPair: AsymmetricCipherKeyPair): (ECDSA.PrivateKey, ECDSA.PublicKey) =
    (
      ECDSA.PrivateKey.fromEcParams(keyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters]),
      ECDSA.PublicKey.fromEcParams(keyPair.getPublic.asInstanceOf[ECPublicKeyParameters])
    )

  private lazy val keyPairs: List[AsymmetricCipherKeyPair] = Source
    .fromResource("ecKeyPairsParameters.csv")
    .getLines()
    .drop(1)
    .map { line =>
      val dq   = line.split(",")
      val dStr = dq(0)
      val qStr = dq(1)
      val prv  = new ECPrivateKeyParameters(new BigInteger(dStr), crypto.curve)
      val q    = new X9ECPoint(crypto.curve.getCurve, Hex.decode(qStr)).getPoint
      val pub  = new ECPublicKeyParameters(q, crypto.curve)
      new AsymmetricCipherKeyPair(pub, prv)
    }
    .toList

  /** Generates a list of n key-pairs with no duplication */
  def listOfExactlyNGen(n: Int): Gen[List[(ECDSA.PrivateKey, ECDSA.PublicKey)]] =
    if (n <= 0)
      Gen.const(List.empty)
    else
      for {
        startIndex      <- Gen.choose(0, keyPairs.length - n)
        asymmetricPairs <- Gen.const(Random.shuffle(keyPairs)).map(_.slice(startIndex, startIndex + n))
      } yield asymmetricPairs.map(asECDSA)

  lazy val asymmetricCipherKeyPairGen: Gen[AsymmetricCipherKeyPair] = Gen.oneOf(keyPairs)

  lazy val fakeSignatureGen: Gen[ECDSASignature] =
    for {
      r <- bigIntGen
      s <- bigIntGen
      v <- byteGen
    } yield ECDSASignature(r, s, v)

  implicit val ecdsaKeyPairGen: Gen[(ECDSA.PrivateKey, ECDSA.PublicKey)] =
    asymmetricCipherKeyPairGen.map(asECDSA)

  final case class KeySet[Scheme <: AbstractSignatureScheme](
      pubKey: SidechainPublicKey,
      prvKey: SidechainPrivateKey,
      crossChainPubKey: Scheme#PublicKey,
      crossChainPrvKey: Scheme#PrivateKey
  )

  implicit def keySetGen[Scheme <: AbstractSignatureScheme](implicit
      crossChainGen: Gen[(Scheme#PrivateKey, Scheme#PublicKey)]
  ): Gen[KeySet[Scheme]] = for {
    (prvKey, pubKey)                     <- ecdsaKeyPairGen
    (crossChainPrvKey, crossChainPubKey) <- crossChainGen
  } yield KeySet(pubKey, prvKey, crossChainPubKey, crossChainPrvKey)

  val ecdsaPrivateKeyGen: Gen[ECDSA.PrivateKey] = ecdsaKeyPairGen.map(_._1)
  val ecdsaPublicKeyGen: Gen[ECDSA.PublicKey]   = ecdsaKeyPairGen.map(_._2)
}
