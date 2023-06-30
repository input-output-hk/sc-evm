package io.iohk.scevm.domain

import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.SignedTransaction.SignedTransactionRLPImplicits._
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.TransactionGenerators.{legacyTransactionGen, transactionGen}
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SignedTransactionSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with EitherValues {

  "Show interpolation" should {
    "interpolates correctly TransactionHash inside List.map" in {
      val (sender, _) = ecdsaKeyPairGen.apply(Gen.Parameters.default, Seed(1000L)).get

      val transactions = Seq(
        SignedTransaction.sign(transactionGen.apply(Gen.Parameters.default, Seed(1000L)).get, sender, None)
      )

      assert(
        show"${transactions.map(_.hash).toList}" == "List(TransactionHash(16cf3c86f44d2d75b4cffc438423dd851532775c642799549e564aa27c74f904))"
      )
      // Enable after cats v2.9
      // assert(show"${transactions.map(_.hash)}" == "List(TransactionHash(16cf3c86f44d2d75b4cffc438423dd851532775c642799549e564aa27c74f904))")
    }
  }

  "SignedTransaction" should {
    "correctly set pointSign for chainId with chain specific signing schema" in {
      forAll(legacyTransactionGen, ecdsaKeyPairGen) { case (tx, (key, _)) =>
        val chainId: ChainId  = ChainId(0x3d)
        val allowedPointSigns = Set((chainId.value * 2 + 35).toByte, (chainId.value * 2 + 36).toByte)
        val result            = SignedTransaction.sign(tx, key, Some(chainId))

        allowedPointSigns should contain(result.signature.v)
      }
    }
  }

  "LegacyTransaction" should {
    "correctly serialize and unserialize to original rlp" in {
      val toAddr: Address = Address.apply("b94f5374fce5edbc8e2a8697c15331677e6ebf0b")
      val tx: LegacyTransaction = LegacyTransaction(
        nonce = Nonce(3),
        gasPrice = 1,
        gasLimit = 2000,
        receivingAddress = toAddr,
        value = 10,
        payload = Hex.decodeUnsafe("5544")
      )
      val sig = ECDSASignature
        .fromBytes(
          Hex.decodeUnsafe(
            "98ff921201554726367d2be8c804a7ff89ccf285ebc57dff8ae4c44b9c19ac4a8887321be575c8095f789dd4c743dfe42c1820f9231f98a962b210e3ac2452a301"
          )
        )
        .value
      val stx = SignedTransaction.apply(
        transaction = tx,
        // hacky change to make the test succeed without regressing the general workflow.
        // The node is currently importing *raw* signature values, and doesn't changes them
        // when building a signed transaction from a signature and a transaction.
        // On the other side, core-geth is updating the signature field v depending on the type
        // of transaction and the expected signature rule (homestead, eip155 or eip2930 for example).
        // The node lacks this feature. Until the signers feature is integrated, we'll keep this localised
        // hack to check for legacy transaction regression.
        // The 27 magic number is taken from the yellow paper and eip155, which stipulate that
        // transaction.v = signature.yParity (here ECDSA.v raw field) + 27
        signature = sig.copy(v = (sig.v + 27).toByte)
      )

      val encoded: Array[Byte] = stx.toBytes
      val e =
        "f86103018207d094b94f5374fce5edbc8e2a8697c15331677e6ebf0b0a8255441ca098ff921201554726367d2be8c804a7ff89ccf285ebc57dff8ae4c44b9c19ac4aa08887321be575c8095f789dd4c743dfe42c1820f9231f98a962b210e3ac2452a3"

      val expected = Hex.decodeAsArrayUnsafe(e)
      encoded shouldBe expected
      encoded.toSignedTransaction shouldBe stx
    }
  }

  "TransactionType01" should {
    "correctly serialized and unserialize to rlp" in {

      // binary values have be taken directly from core-geth own tests
      // see https://github.com/ethereum/go-ethereum/blob/a580f7d6c54812ef47df94c6ffc974c9dbc48245/core/types/transaction_test.go#L71

      val toAddr: Address = Address.apply("b94f5374fce5edbc8e2a8697c15331677e6ebf0b")
      val tx: TransactionType01 = TransactionType01(
        chainId = 1, // ethereum mainnet, used by the core-geth test
        nonce = Nonce(3),
        gasPrice = 1,
        gasLimit = 25000,
        receivingAddress = toAddr,
        value = 10,
        payload = Hex.decodeUnsafe("5544"),
        accessList = Nil
      )
      val stx = SignedTransaction.apply(
        transaction = tx,
        ECDSASignature
          .fromBytes(
            Hex.decodeUnsafe(
              "c9519f4f2b30335884581971573fadf60c6204f59a911df35ee8a540456b266032f1e8e2c5dd761f9e4f88f41c8310aeaba26a8bfcdacfedfa12ec3862d3752101"
            )
          )
          .value
      )
      val encodedSignedTransaction: Array[Byte] = stx.toBytes
      val e =
        "01f8630103018261a894b94f5374fce5edbc8e2a8697c15331677e6ebf0b0a825544c001a0c9519f4f2b30335884581971573fadf60c6204f59a911df35ee8a540456b2660a032f1e8e2c5dd761f9e4f88f41c8310aeaba26a8bfcdacfedfa12ec3862d37521"
      val expected = Hex.decodeAsArrayUnsafe(e)
      encodedSignedTransaction shouldBe expected
      encodedSignedTransaction.toSignedTransaction shouldBe stx
      true shouldBe true
    }
  }

  "TransactionType02" should {
    "correctly serialized and unserialize to rlp" in {

      // binary values have be taken directly from core-geth own tests
      // see https://github.com/ethereum/go-ethereum/blob/master/core/types/block_test.go#L118

      val toAddr: Address = Address.apply("095e7baea6a6c7c4c2dfeb977efac326af552d87")
      val tx: TransactionType02 = TransactionType02(
        chainId = 1, // ethereum mainnet, used by the core-geth test
        nonce = Nonce.Zero,
        maxPriorityFeePerGas = 0,
        maxFeePerGas = 1000000000,
        gasLimit = 123457,
        receivingAddress = toAddr,
        value = 0,
        payload = ByteString.empty,
        accessList = Nil
      )
      val stx = SignedTransaction.apply(
        transaction = tx,
        ECDSASignature
          .fromBytes(
            Hex.decodeUnsafe(
              "fe38ca4e44a30002ac54af7cf922a6ac2ba11b7d22f548e8ecb3f51f41cb31b06de6a5cbae13c0c856e33acf021b51819636cfc009d39eafb9f606d546e305a800"
            )
          )
          .value
      )
      val encodedSignedTransaction: Array[Byte] = stx.toBytes
      encodedSignedTransaction.toSignedTransaction shouldBe stx
    }
  }
}
