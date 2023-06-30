package io.iohk.scevm.keystore

import cats.effect.IO
import cats.effect.std.Random
import io.iohk.ethereum.Crypto
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.KeyStoreConfig
import io.iohk.scevm.domain.Address
import io.iohk.scevm.keystore.KeyStore.{DecryptionFailed, IOError, KeyNotFound, PassphraseTooShort}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.wallet.Wallet
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, EitherValues}

import java.io.{File => JFile}
import java.security.SecureRandom
import scala.reflect.io.File

class KeyStoreSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterEach
    with ScalaFutures
    with NormalPatience
    with IOSupport
    with EitherValues {

  val secureRandom: SecureRandom  = new SecureRandom()
  implicit val random: Random[IO] = Random.javaUtilRandom[IO](secureRandom).ioValue
  implicit val crypto: Crypto[IO] = new Crypto[IO](secureRandom)

  "KeyStore" should {
    "create new accounts" in {
      val keyStoreConfig: KeyStoreConfig = TestKeyStoreConfig.config()
      (for {
        keyStore <- KeyStore.create[IO](keyStoreConfig)
        newAddr1 <- keyStore.newAccount("new!Super'p@ssphr@se").map(_.toOption.get)
        newAddr2 <- keyStore.newAccount("another!Super'p@ssphr@se").map(_.toOption.get)

        listOfNewAccounts <- keyStore.listAccounts().map(_.toOption.get)
      } yield {
        listOfNewAccounts.toSet shouldEqual Set(newAddr1, newAddr2)
        listOfNewAccounts.length shouldEqual 2
      }).unsafeRunSync()
    }

    "fail to create account with a passphrase too short" in new TestSetup {
      val result = keyStore.newAccount("pwrd").unsafeRunSync()
      result shouldEqual Left(PassphraseTooShort(keyStoreConfig.minimalPassphraseLength))
    }

    "allow 0 length passphrase when configured" in new TestSetup {
      val result = keyStore.newAccount("").unsafeRunSync()
      assert(result.isRight)
    }

    "do not allow 0 length passphrase when configured" in new TestSetup {
      val newKeyStore = getKeyStore(noEmptyAllowedConfig)
      val result      = newKeyStore.newAccount("").unsafeRunSync()
      result shouldBe Left(PassphraseTooShort(noEmptyAllowedConfig.minimalPassphraseLength))
    }

    "do not allow a passphrase too short, when empty is allowed" in new TestSetup {
      val newKeyStore = getKeyStore(noEmptyAllowedConfig)
      val result      = newKeyStore.newAccount("pwrd").unsafeRunSync()
      result shouldBe Left(PassphraseTooShort(noEmptyAllowedConfig.minimalPassphraseLength))
    }

    "allow to create account with proper length passphrase, when empty is allowed" in new TestSetup {
      val newKeyStore = getKeyStore(noEmptyAllowedConfig)
      val result      = newKeyStore.newAccount("new!Super'p@ssphr@seNew").unsafeRunSync()
      assert(result.isRight)
    }

    "unlock an account provided the correct passphrase" in new TestSetup {
      val passphrase = "Super'p@ssphr@se!"
      keyStore.importPrivateKey(key1, passphrase).unsafeRunSync()
      val wallet = keyStore.unlockAccount(addr1, passphrase).map(_.toOption.get).unsafeRunSync()
      wallet shouldEqual Wallet(addr1, key1)
    }

    "return an error when unlocking an account with a wrong passphrase" in new TestSetup {
      keyStore.importPrivateKey(key1, "Super'p@ssphr@se!").unsafeRunSync()
      val res = keyStore.unlockAccount(addr1, "justSuper").unsafeRunSync()
      res shouldEqual Left(DecryptionFailed)
    }

    "return an error when trying to unlock an unknown account" in new TestSetup {
      val res = keyStore.unlockAccount(addr1, "bbb").unsafeRunSync()
      res shouldEqual Left(KeyNotFound)
    }

    "return an error when the keystore dir cannot be read or written" in new TestSetup {
      clearKeyStore()
      val key  = ECDSA.PrivateKey.fromHex("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f").value
      val res1 = keyStore.importPrivateKey(key, "Super'p@ssphr@se!").unsafeRunSync()
      res1 should matchPattern { case Left(IOError(_)) => }

      val res2 = keyStore.newAccount("Super'p@ssphr@se!").unsafeRunSync()
      res2 should matchPattern { case Left(IOError(_)) => }

      val res3 = keyStore.listAccounts().unsafeRunSync()
      res3 should matchPattern { case Left(IOError(_)) => }
    }

    "import and list accounts" in new TestSetup {
      (for {
        listBeforeImport <- keyStore.listAccounts().map(_.toOption.get)
        _                 = listBeforeImport shouldEqual Nil

        // We sleep between imports so that dates of keyfiles' names are different
        res1 <- keyStore.importPrivateKey(key1, "Super'p@ssphr@se!A").map(_.toOption.get)
        // Thread.sleep(500)
        res2 <- keyStore.importPrivateKey(key2, "Super'p@ssphr@se!B").map(_.toOption.get)
        // Thread.sleep(500)
        res3 <- keyStore.importPrivateKey(key3, "Super'p@ssphr@se!C").map(_.toOption.get)

        _ = res1 shouldEqual addr1
        _ = res2 shouldEqual addr2
        _ = res3 shouldEqual addr3

        listAfterImport <- keyStore.listAccounts().map(_.toOption.get)

        // result should be ordered by creation date
      } yield listAfterImport shouldEqual List(addr1, addr2, addr3)).unsafeRunSync()
    }

    "fail to import a key twice" in new TestSetup {
      (for {
        resAfterFirstImport <- keyStore.importPrivateKey(key1, "Super'p@ssphr@se!")
        resAfterDupImport   <- keyStore.importPrivateKey(key1, "Super'p@ssphr@se!")

        _ = resAfterFirstImport shouldEqual Right(addr1)
        _ = resAfterDupImport shouldBe Left(KeyStore.DuplicateKeySaved)

        //Only the first import succeeded
        listAfterImport <- keyStore.listAccounts().map(_.toOption.get)
      } yield {
        listAfterImport.toSet shouldEqual Set(addr1)
        listAfterImport.length shouldEqual 1
      }).unsafeRunSync()
    }
  }

  trait TestSetup extends EitherValues {
    val keyStoreConfig: KeyStoreConfig = TestKeyStoreConfig.config()

    val noEmptyAllowedConfig: KeyStoreConfig = keyStoreConfig.copy(allowNoPassphrase = false)

    val keyStore: KeyStore[IO] = KeyStore.create[IO](keyStoreConfig).unsafeRunSync()

    def getKeyStore(config: KeyStoreConfig): KeyStore[IO] =
      KeyStore.create[IO](config).unsafeRunSync()

    val key1: ECDSA.PrivateKey =
      ECDSA.PrivateKey.fromHex("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f").value
    val addr1: Address = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val key2: ECDSA.PrivateKey =
      ECDSA.PrivateKey.fromHex("ee9fb343c34856f3e64f6f0b5e2abd1b298aaa76d0ffc667d00eac4582cb69ca").value
    val addr2: Address = Address(Hex.decode("f1c8084f32b8ef2cee7099446d9a6a185d732468"))
    val key3: ECDSA.PrivateKey =
      ECDSA.PrivateKey.fromHex("ed341f91661a05c249c36b8c9f6d3b796aa9f629f07ddc73b04b9ffc98641a50").value
    val addr3: Address = Address(Hex.decode("d2ecb1332a233d314c30fe3b53f44541b7a07a9e"))

    def clearKeyStore(): Unit =
      File(new JFile(keyStoreConfig.keyStoreDir)).toDirectory.deleteRecursively()
  }
}
