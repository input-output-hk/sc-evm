package io.iohk.scevm.network.p2p.messages

import io.iohk.ethereum.rlp.{PrefixedRLPEncodable, RLPList, decode}
import io.iohk.scevm.domain.{SignedTransaction, TypedTransaction}
import io.iohk.scevm.network.RequestId
import io.iohk.scevm.network.p2p.messages.OBFT1.{
  GetPooledTransactions,
  NewPooledTransactionHashes,
  PooledTransactions,
  SignedTransactions
}
import io.iohk.scevm.testing.TransactionGenerators.{signedTxSeqGen, transactionHashGen}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

// TODO update once SignedTransaction is updated
// import io.iohk.bytes.ByteString
// import io.iohk.ethereum.crypto
// import io.iohk.scevm.domain.{Address, SignedTransaction, Transaction}
// import io.iohk.scevm.config.Config
// import org.bouncycastle.math.ec.ECPoint
// import org.bouncycastle.util.encoders.Hex
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatest.matchers.should.Matchers

class TransactionSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "rlp encoding then decoding transactions sequence" should {
    "give back the initial transactions sequence" in {
      forAll(signedTxSeqGen()) { (originalSignedTransactionSeq: Seq[SignedTransaction]) =>
        // encode it
        val encodedSignedTransactionSeq: Array[Byte] = SignedTransactions(originalSignedTransactionSeq).toBytes
        val encodedPooledTransactionSeq: Array[Byte] =
          PooledTransactions(RequestId(0), originalSignedTransactionSeq).toBytes
        // decode it
        val SignedTransactions(decodedSignedTransactionSeq)    = decode[SignedTransactions](encodedSignedTransactionSeq)
        val PooledTransactions(_, decodedPooledTransactionSeq) = decode[PooledTransactions](encodedPooledTransactionSeq)
        decodedSignedTransactionSeq shouldEqual originalSignedTransactionSeq
        decodedPooledTransactionSeq shouldEqual originalSignedTransactionSeq
      }
    }
  }

  "PrefixedRLPEncodable" should {
    "reject invalid transaction type outside of [0, 0x7f]" in {
      import TypedTransaction._
      forAll(
        Arbitrary.arbitrary[Byte].suchThat(b => b < MinAllowedType || b > MaxAllowedType)
      ) { transactionType =>
        an[RuntimeException] shouldBe thrownBy(PrefixedRLPEncodable(transactionType, RLPList()))
      }
    }
    "accept valid transaction type [0, 0x7f]" in {
      import TypedTransaction._
      forAll(Gen.choose[Byte](MinAllowedType, MaxAllowedType)) { transactionType =>
        PrefixedRLPEncodable(transactionType, RLPList())
      }
    }
  }

  "NewPooledTransactionHashes" should {
    "round trip via RLPx serialization" in {
      forAll(Gen.listOf(transactionHashGen)) { hashes =>
        val msg                  = NewPooledTransactionHashes(hashes)
        val encoded: Array[Byte] = msg.toBytes
        decode[NewPooledTransactionHashes](encoded) shouldEqual msg
      }
    }
  }

  "PooledTransactionHashes" should {
    "round trip via RLPx serialization" in {
      forAll(Gen.listOf(transactionHashGen)) { hashes =>
        val msg                  = GetPooledTransactions(hashes)
        val encoded: Array[Byte] = msg.toBytes
        decode[GetPooledTransactions](encoded) shouldEqual msg
      }
    }
  }
}

// class TransactionSpec extends AnyFlatSpec with Matchers {

//   val blockchainConfig = Config.blockchains.blockchainConfig

//   val rawPublicKey: Array[Byte] =
//     Hex.decode(
//       "044c3eb5e19c71d8245eaaaba21ef8f94a70e9250848d10ade086f893a7a33a06d7063590e9e6ca88f918d7704840d903298fe802b6047fa7f6d09603eba690c39"
//     )
//   val publicKey: ECPoint = crypto.curve.getCurve.decodePoint(rawPublicKey)
//   val address: Address = Address(crypto.kec256(rawPublicKey.tail).slice(12, 32))

//   val validTx: Transaction = Transaction(
//     nonce = 172320,
//     gasPrice = BigInt("50000000000"),
//     gasLimit = 90000,
//     receivingAddress = Address(Hex.decode("1c51bf013add0857c5d9cf2f71a7f15ca93d4816")),
//     value = BigInt("1049756850000000000"),
//     payload = ByteString.empty
//   )

//   val validTransactionSignatureOldSchema: SignedTransaction = SignedTransaction(
//     validTx,
//     pointSign = 28.toByte,
//     signatureRandom = ByteString(Hex.decode("cfe3ad31d6612f8d787c45f115cc5b43fb22bcc210b62ae71dc7cbf0a6bea8df")),
//     signature = ByteString(Hex.decode("57db8998114fae3c337e99dbd8573d4085691880f4576c6c1f6c5bbfe67d6cf0")),
//     chainId = blockchainConfig.chainId
//   )

//   val invalidTransactionSignatureNewSchema: SignedTransaction = SignedTransaction(
//     validTx,
//     pointSign = -98.toByte,
//     signatureRandom = ByteString(Hex.decode("cfe3ad31d6612f8d787c45f115cc5b43fb22bcc210b62ae71dc7cbf0a6bea8df")),
//     signature = ByteString(Hex.decode("57db8998114fae3c337e99dbd8573d4085691880f4576c6c1f6c5bbfe67d6cf0")),
//     chainId = blockchainConfig.chainId
//   )

//   val invalidStx: SignedTransaction = SignedTransaction(
//     validTx.copy(gasPrice = 0),
//     pointSign = -98.toByte,
//     signatureRandom = ByteString(Hex.decode("cfe3ad31d6612f8d787c45f115cc5b43fb22bcc210b62ae71dc7cbf0a6bea8df")),
//     signature = ByteString(Hex.decode("57db8998114fae3c337e99dbd8573d4085691880f4576c6c1f6c5bbfe67d6cf0")),
//     chainId = blockchainConfig.chainId
//   )

//   val rawPublicKeyForNewSigningScheme: Array[Byte] =
//     Hex.decode(
//       "048fc6373a74ad959fd61d10f0b35e9e0524de025cb9a2bf8e0ff60ccb3f5c5e4d566ebe3c159ad572c260719fc203d820598ee5d9c9fa8ae14ecc8d5a2d8a2af1"
//     )
//   val publicKeyForNewSigningScheme: ECPoint = crypto.curve.getCurve.decodePoint(rawPublicKeyForNewSigningScheme)
//   val addreesForNewSigningScheme: Address = Address(crypto.kec256(rawPublicKeyForNewSigningScheme.tail).slice(12, 32))

//   val validTransactionForNewSigningScheme: Transaction = Transaction(
//     nonce = 587440,
//     gasPrice = BigInt("20000000000"),
//     gasLimit = 90000,
//     receivingAddress = Address(Hex.decode("77b95d2028c741c038735b09d8d6e99ea180d40c")),
//     value = BigInt("1552986466088074000"),
//     payload = ByteString.empty
//   )

//   val validSignedTransactionForNewSigningScheme: SignedTransaction = SignedTransaction(
//     tx = validTransactionForNewSigningScheme,
//     pointSign = -98.toByte,
//     signatureRandom = ByteString(Hex.decode("1af423b3608f3b4b35e191c26f07175331de22ed8f60d1735f03210388246ade")),
//     signature = ByteString(Hex.decode("4d5b6b9e3955a0db8feec9c518d8e1aae0e1d91a143fbbca36671c3b89b89bc3")),
//     blockchainConfig.chainId
//   )

//   val stxWithInvalidPointSign: SignedTransaction = SignedTransaction(
//     validTx,
//     pointSign = 26.toByte,
//     signatureRandom = ByteString(Hex.decode("cfe3ad31d6612f8d787c45f115cc5b43fb22bcc210b62ae71dc7cbf0a6bea8df")),
//     signature = ByteString(Hex.decode("57db8998114fae3c337e99dbd8573d4085691880f4576c6c1f6c5bbfe67d6cf0")),
//     blockchainConfig.chainId
//   )

//   it should "not recover sender public key for new sign encoding schema if there is no chain_id in signed data" in {
//     SignedTransaction.getSender(invalidTransactionSignatureNewSchema) shouldNot be(Some(address))
//   }

//   it should "recover sender address" in {
//     SignedTransaction.getSender(validTransactionSignatureOldSchema) shouldEqual Some(address)
//   }

//   it should "recover sender for new sign encoding schema if there is chain_id in signed data" in {
//     SignedTransaction.getSender(validSignedTransactionForNewSigningScheme) shouldBe Some(addreesForNewSigningScheme)
//   }

//   it should "recover false sender address for invalid transaction" in {
//     SignedTransaction.getSender(invalidStx) shouldNot be(Some(address))
//   }

//   it should "not recover a sender address for transaction with invalid point sign" in {
//     SignedTransaction.getSender(stxWithInvalidPointSign) shouldBe None
//   }

//   it should "recover the correct sender for tx in block 46147" in {
//     val stx: SignedTransaction = SignedTransaction(
//       tx = Transaction(
//         nonce = BigInt(0),
//         gasPrice = BigInt("50000000000000"),
//         gasLimit = BigInt(21000),
//         receivingAddress = Address(ByteString(Hex.decode("5df9b87991262f6ba471f09758cde1c0fc1de734"))),
//         value = BigInt(31337),
//         payload = ByteString.empty
//       ),
//       pointSign = 28.toByte,
//       signatureRandom =
//         ByteString(BigInt("61965845294689009770156372156374760022787886965323743865986648153755601564112").toByteArray),
//       signature =
//         ByteString(BigInt("31606574786494953692291101914709926755545765281581808821704454381804773090106").toByteArray),
//       chainId = blockchainConfig.chainId
//     )

//     SignedTransaction.getSender(stx).get shouldBe Address(
//       ByteString(Hex.decode("a1e4380a3b1f749673e270229993ee55f35663b4"))
//     )
//   }

// }
