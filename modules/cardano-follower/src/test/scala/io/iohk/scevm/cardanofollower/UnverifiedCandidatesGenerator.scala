package io.iohk.scevm.cardanofollower

import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.{ECDSASignature, EdDSA, kec256, keyPairToByteArrays}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.trustlesssidechain.cardano.{Blake2bHash28, MainchainTxHash, UtxoId}
import org.scalatest.freespec.AnyFreeSpec

import java.security.SecureRandom

class UnverifiedCandidatesGenerator extends AnyFreeSpec {
  private val random = new SecureRandom(ByteString.fromInts(42).toArray)

  "generate candidates" in {
    val txInput = UtxoId(
      MainchainTxHash.decodeUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"),
      0
    )
    val message = txInput.txHash.value.concat(ByteString.fromInts(txInput.index)).toArray

    val keyPair               = crypto.keyPairFromPrvKey(BigInt(42))
    val ecdsaSignature        = ECDSASignature.sign(kec256(message), keyPair)
    val (ecdsaPriv, ecdsaPub) = keyPairToByteArrays(keyPair)

    val (eddsaPrivateKey, eddsaPublicKey) = EdDSA.generateKeyPair(random)
    val messageForEddsa                   = Blake2bHash28.hash(ByteString(message)).bytes
    val eddsaSig                          = eddsaPrivateKey.sign(messageForEddsa)

    //scalastyle:off regex
    println(s"ecdsa prv length: ${ecdsaPriv.length}")
    println(s"ecdsa pub length: ${ecdsaPub.length}")
    println(s"eddsa priv length: ${eddsaPrivateKey.bytes.length}")
    println(s"eddsa pub length: ${eddsaPublicKey.bytes.length}")
    println(show"TxInput: ${txInput}")
    println(s"Message: ${Hex.toHexString(message)}")
    println(s"ECDSA pubKey: ${Hex.toHexString(ecdsaPub)}")
    println(s"ECDSA privKey: ${Hex.toHexString(ecdsaPriv)}")
    println(s"ECDSA signature: ${Hex.toHexString(ecdsaSignature.toBytes.toArray)}")
    println(s"EdDSA pubKey: ${Hex.toHexString(eddsaPublicKey.key.getEncoded)}")
    println(s"EdDSA prvKey: ${Hex.toHexString(eddsaPrivateKey.key.getEncoded)}")
    println(s"EdDSA signature: ${eddsaSig}")
    //scalastyle:on regex
  }
}
