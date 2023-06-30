package io.iohk.scevm.domain

import cats.syntax.all._
import io.iohk.ethereum.crypto.kec256
import io.iohk.scevm.domain.SignedTransaction.SignedTransactionRLPImplicits.{SignedTransactionDec, SignedTransactionEnc}
import io.iohk.scevm.domain.SignedTransaction.getSender
import io.iohk.scevm.testing.TransactionGenerators._
import io.iohk.scevm.testing.{CryptoGenerators, TestCoreConfigs}
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class TransactionSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  "Show instance" should {
    "print the expected format for legacy transaction" in {
      val legacyTransaction: Transaction = legacyTransactionGen.apply(Gen.Parameters.default, Seed(1000L)).get

      assert(
        legacyTransaction.show == "LegacyTransaction(nonce = Nonce(58122207924380471952839376328342091304284198588914243681378393853141592178474), gasPrice = 111780503902070228939280597878741991344113852792903244476957174904930315788164, gasLimit = 71377098127192621694477867588821605272066937576411096128079886702195064537855, receivingAddress = Some(63097fe636805b2fdd010101ff6b53bfaf8cffcf), value = 13568936992212502347919296049265620420986016405462692425854517997622536536068, payload = 0101e18071808001ff002fa7a165bc0001647fffda1d18ff7f8028017f0180ff7f7f007f7f008001012b7100aa808101ffff5f80807f8fc944d1499fff6280928ac5017fddafe080cfff4e9f010045800000fb007fc0ff7079eef280c2ff3f6f7f7f8080de7f808000b0600b8080800101be1ae21b8a58bf01e437019bff7f01da7f01fff5d08001b6001de380b600007fce67f4a3dbff7f34d50102db96c08038ffc97960f3f4ffa501ff7ffffbffff000081f17505ff5ede800141ff8e0d2680c8db7f92927f01321d11e77aff01dbc3ffda00a87f4e7f0101c0ff01ffff0101eb00ffe26a4e23520180b380e0ffae1e2a00013d16207f40987f948001a663)"
      )
    }

    "print the expected format for Type01 transaction" in {
      val type01Transaction = typedTransactionGen.apply(Gen.Parameters.default, Seed(1000L)).get

      assert(
        type01Transaction.show == "TransactionType01(chainId = 57897806264927805709794258943523168678196801560054185382298073391367003777783, nonce = Nonce(15162957312878338833799759812036544335460102570011268355013525707617820705949), gasPrice = 93179110302668472285689456374329416685748427055672054593610306519575168221027, gasLimit = 4296794085490619636629212615030330387623379464453508358106845049744045080553, receivingAddress = Some(010098800018c300008eff068017000101800401), value = 850739921697398129729804240908613914012003275151782915675469544527384084351, payload = 7f007f7f008001012b7100aa808101ffff5f80807f8fc944d1499fff6280928ac5017fddafe080cfff4e9f010045800000fb007fc0ff7079eef280c2ff3f6f7f7f8080de7f808000b0600b8080800101be1ae21b8a58bf01e437019bff7f01da7f01fff5d08001b6001de380b600007fce67f4a3dbff7f34d50102db96c08038ffc97960f3f4ffa501ff7ffffbffff000081f17505ff5ede800141ff8e0d2680c8db7f92927f01321d11e77aff01dbc3ffda00a87f4e7f0101c0ff01ffff0101eb00ffe26a4e23520180b380e0ffae1e2a00013d16207f40987f948001a663c7807f000000be7f7701097fffff34017f80ffff8066390168003f017fff7f4701, accessList = Nil$())"
      )
    }

    "print the expected format for Type02 transaction" in {
      val type02Transaction = typedTransactionGen.apply(Gen.Parameters.default, Seed(1001L)).get

      assert(
        type02Transaction.show == "TransactionType02(chainId = 226304798366342623932507408255095946564939769328652739937141935148397101439, nonce = Nonce(582265828861580133667200028896745506709941922604020914954585224405088698368), maxPriorityFeePerGas = 95695510732113043942334430611241378026472843803230472796461377802016500200154, maxFeePerGas = 771159739855172479337707519721182605301878028731902924408489049662100215297, gasLimit = 57462383753159452474613632916038473298575324556755610559665390227722078080127, receivingAddress = Some(ff01ff74d87b0be2f66eb4c1895dd9bb01017f7f), value = 58120444548583456122746176347265900867290534380690518732718714386968305810141, payload = ea7f7eff7f167a3d527f7f01d661807f01f0d27f000f8012038097ffa47fe880807f01a942bbff0180570cffffd4b226010080807fee010101f08077a3ee0065801e7f808000498064af51ff83bc947f8080868000f5019253c60159d1ce01cb7f7f48977f01ec0101ff31fa010080d180d9d37f4601ff1e0100f52c209e07c8a1bfff6b1ed6ea937f7f7f00b2fff201290100015e5d4a5dfffd2cde00be4e7f00ff01ff5db8807fa00101e66700ff013e7f3916c8e3b813ff80868086800092800080007e80270100dab727008020007f08802f6a007ff201010124f650ff7f7fda44801a016101a4007f01457f8080ffe301b87fff00ff007f7f58267f7f7f, accessList = $colon$colon(head = AccessListItem(address = ab01000100002721018c80cb57a501ff00ffaada, storageKeys = List(72874494088132714240302178741083834911208708083435485803223657244125967797373)), next = Nil$()))"
      )
    }
  }

  "Transaction (all types)" when {
    "signing transaction, encoding and decoding it" should {
      "allow to retrieve the proper sender" in {
        forAll(transactionGen, CryptoGenerators.ecdsaKeyPairGen) {
          case (originalTransaction: Transaction, (privateKey, pubKey)) =>
            implicit val chainId = TestCoreConfigs.chainId

            val originalSenderAddress = {
              // You get a public address for your account by taking the last 20 bytes of the Keccak-256 hash
              // of the public key and adding 0x to the beginning.
              val hashedPublickKey = kec256(pubKey.bytes)
              val slice            = hashedPublickKey.slice(hashedPublickKey.length - 20, hashedPublickKey.length)
              Address(slice)
            }

            val originalSignedTransaction = SignedTransaction.sign(
              originalTransaction,
              privateKey,
              Some(chainId)
            ) // check for proper signature content
            getSender(originalSignedTransaction) shouldEqual Some(originalSenderAddress)

            // encode it
            val encodedSignedTransaction: Array[Byte] = originalSignedTransaction.toBytes

            // decode it
            val decodedSignedTransaction = encodedSignedTransaction.toSignedTransaction

            // resolve original sender getSender(originalSignedTransaction.tx)
            getSender(decodedSignedTransaction) shouldEqual getSender(originalSignedTransaction)
        }
      }
    }
  }
}
