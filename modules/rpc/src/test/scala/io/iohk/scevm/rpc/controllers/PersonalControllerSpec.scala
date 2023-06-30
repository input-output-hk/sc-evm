package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.StateStorage
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.validators.SignedTransactionError.TransactionNotEnoughGasForIntrinsicError
import io.iohk.scevm.exec.validators.{SignedTransactionValid, SignedTransactionValidator}
import io.iohk.scevm.keystore.KeyStore
import io.iohk.scevm.keystore.KeyStore.{DecryptionFailed, IOError}
import io.iohk.scevm.rpc.controllers.PersonalController._
import io.iohk.scevm.rpc.domain.JsonRpcError.{AccountLocked, LogicError}
import io.iohk.scevm.rpc.domain.{JsonRpcError, TransactionRequest}
import io.iohk.scevm.rpc.router.{AccountServiceStub, NonceServiceStub}
import io.iohk.scevm.testing.{TestCoreConfigs, fixtures}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import io.iohk.scevm.wallet.Wallet
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Duration
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

class PersonalControllerSpec extends AsyncWordSpec with AsyncMockFactory with Matchers {
  import PersonalControllerFixture._
  import cats.effect.unsafe.implicits.global

  val keyStore: KeyStore[IO]                        = mock[KeyStore[IO]]
  val genesisHeader: ObftHeader                     = fixtures.GenesisBlock.header
  implicit val current: CurrentBranch.Signal[IO]    = Signal.constant(CurrentBranch(genesisHeader, genesisHeader))
  val blockchainConfig: BlockchainConfig            = TestCoreConfigs.blockchainConfig
  val stateStorage: StateStorage                    = mock[StateStorage]
  val nonceProvider                                 = new NonceServiceStub[IO]()
  val signedTxValidator: SignedTransactionValidator = mock[SignedTransactionValidator]

  val personalController =
    new PersonalController(
      keyStore,
      NewTransactionListenerStub(),
      blockchainConfig,
      nonceProvider,
      signedTxValidator,
      AccountServiceStub(Account(nonce, 2 * txValue))
    )

  "PersonalService" when {
    "creating new accounts" should {
      "create account successfully if not error occurs" in {
        (keyStore.newAccount _).expects(passphraseAsString).returning(IO(Right(address)))

        personalController
          .newAccount(passphrase)
          .map(_.toOption.get)
          .map { response =>
            response shouldEqual address
          }
          .unsafeToFuture()
      }

      "handle passphrase too short error" in {
        val passphrase = "short"
        (keyStore.newAccount _).expects(passphrase).returning(IO(Left(KeyStore.PassphraseTooShort(7))))

        personalController
          .newAccount(Passphrase(passphrase))
          .map {
            case Right(_)    => fail("PersonalController.PassphraseTooShort expected")
            case Left(error) => error shouldBe JsonRpcError.PassphraseTooShort(7)
          }
          .unsafeToFuture()
      }
    }

    "listing accounts" should {
      "return list of account managed by the node" in {
        val addresses = List(123, 42, 1).map(Address(_))
        (() => keyStore.listAccounts()).expects().returning(IO(Right(addresses)))

        personalController
          .listAccounts()
          .map(_.toOption.get)
          .map { response =>
            response shouldEqual addresses
          }
          .unsafeToFuture()
      }

      "translate KeyStore errors to JsonRpc errors" in {
        (() => keyStore.listAccounts()).expects().returning(IO(Left(IOError("boom!"))))
        personalController
          .listAccounts()
          .map {
            case Right(_)    => fail("LogicError expected")
            case Left(error) => error shouldBe LogicError("boom!")
          }
          .unsafeToFuture()
      }
    }

    "unlocking accounts" should {
      "unlock an account given a correct passphrase" in {
        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))
        val request = UnlockAccountRequest(address, passphrase, None)

        personalController
          .unlockAccount(request)
          .map(_.toOption.get)
          .map { response =>
            personalController.unlockedWallets.underlying.contains(address) shouldBe true
            response shouldEqual true
          }
          .unsafeToFuture()
      }

      "unlock an account for a specific duration" in {
        val duration = 100

        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))
        val request = UnlockAccountRequest(address, passphrase, Some(Duration.ofMillis(duration)))

        val test = for {
          response <- personalController.unlockAccount(request).map(_.toOption.get)
          _ = {
            personalController.unlockedWallets.underlying.contains(address) shouldBe true
            response shouldEqual true
          }
          _     <- IO.sleep(FiniteDuration.apply(duration, MILLISECONDS))
          result = personalController.unlockedWallets.get(address) shouldBe None
        } yield result
        test.unsafeRunSync()
      }

      "translate KeyStore errors to JsonRpc errors" in {
        (for {
          _ <- {
            (keyStore.unlockAccount _).expects(*, *).returning(IO(Left(KeyStore.KeyNotFound)))
            personalController
              .unlockAccount(UnlockAccountRequest(Address(42), Passphrase("passphrase"), None))
              .map {
                case Right(_)    => fail("KeyNotFound expected")
                case Left(error) => error shouldBe JsonRpcError.KeyNotFound
              }
          }
          result <- {
            (keyStore.unlockAccount _).expects(*, *).returning(IO(Left(KeyStore.DecryptionFailed)))
            personalController
              .unlockAccount(UnlockAccountRequest(Address(42), Passphrase("passphrase"), None))
              .map {
                case Right(_)    => fail("InvalidPassphrase expected")
                case Left(error) => error shouldBe JsonRpcError.InvalidPassphrase
              }
          }
        } yield result).unsafeToFuture()
      }
    }

    "locking accounts" should {
      "lock an unlocked account" in {
        (keyStore.unlockAccount _)
          .expects(address, passphraseAsString)
          .returning(IO(Right(wallet)))

        personalController
          .unlockAccount(UnlockAccountRequest(address, passphrase, None))
          .unsafeToFuture()
          .flatMap { _ =>
            personalController.unlockedWallets.underlying.contains(address) shouldBe true

            personalController
              .lockAccount(address)
              .map(_.toOption.get)
              .map { response =>
                personalController.unlockedWallets.underlying.contains(address) shouldBe false
                response shouldEqual true
              }
              .unsafeToFuture()
          }
      }
    }

    "sending transactions (personal_sendTransaction)" should {
      "send a transaction (given sender address and a passphrase)" in {
        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))

        implicit val current: CurrentBranch.Signal[IO] = Signal.constant(CurrentBranch(genesisHeader, obftHeader))

        (signedTxValidator
          .validateForMempool(
            _: SignedTransaction,
            _: Account,
            _: BlockNumber,
            _: BigInt,
            _: BigInt
          )(
            _: BlockchainConfig
          ))
          .expects(*, *, *, *, *, *)
          .returning(Right(SignedTransactionValid))

        val personalController =
          new PersonalController(
            keyStore,
            NewTransactionListenerStub(),
            blockchainConfig,
            new NonceServiceStub[IO](nonce),
            signedTxValidator,
            AccountServiceStub(Account(nonce, 2 * txValue))
          )

        personalController
          .sendTransaction(txRequest, passphrase)
          .map(_.toOption.get)
          .map { response =>
            response shouldBe TransactionHash(signedTx.hash.byteString)
          }
          .unsafeToFuture()
      }

      "send a transaction when having pending txs from the same sender" in {
        val newNonce = nonce.increaseOne
        val newTx    = wallet.signTx(txRequest.toTransaction(newNonce), Some(chainId))
        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))

        implicit val current: CurrentBranch.Signal[IO] = Signal.constant(CurrentBranch(genesisHeader, obftHeader))

        (signedTxValidator
          .validateForMempool(
            _: SignedTransaction,
            _: Account,
            _: BlockNumber,
            _: BigInt,
            _: BigInt
          )(
            _: BlockchainConfig
          ))
          .expects(*, *, *, *, *, *)
          .returning(Right(SignedTransactionValid))

        val personalController =
          new PersonalController(
            keyStore,
            NewTransactionListenerStub(),
            blockchainConfig,
            new NonceServiceStub[IO](newNonce),
            signedTxValidator,
            AccountServiceStub(Account(nonce, 2 * txValue))
          )

        personalController
          .sendTransaction(txRequest, passphrase)
          .map(_.toOption.get)
          .map { response =>
            response shouldBe TransactionHash(newTx.hash.byteString)
          }
          .unsafeToFuture()
      }

      "fail to send a transaction when given a wrong passphrase" in {
        (keyStore.unlockAccount _)
          .expects(address, passphraseAsString)
          .returning(IO(Left(KeyStore.DecryptionFailed)))

        personalController
          .sendTransaction(txRequest, passphrase)
          .map {
            case Right(_)    => fail("InvalidPassphrase expected")
            case Left(error) => error shouldBe JsonRpcError.InvalidPassphrase
          }
          .unsafeToFuture()
      }

      "fail to send a transaction when Gas Limit < Intrinsic Gas" in {
        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))

        setMocksToFailWithTransactionNotEnoughGasForIntrinsicError()

        val personalController =
          new PersonalController(
            keyStore,
            NewTransactionListenerStub(),
            blockchainConfig,
            new NonceServiceStub[IO](),
            signedTxValidator,
            AccountServiceStub(Account(nonce, 2 * txValue))
          )

        personalController
          .sendTransaction(txRequest, passphrase)
          .map {
            case Right(_) => fail("TransactionNotEnoughGasForIntrinsicError(1000, 76580) expected")
            case Left(error) =>
              error shouldBe JsonRpcError.InvalidParams(
                "Insufficient transaction gas limit: Transaction gas limit (1000) < transaction intrinsic gas (76580)."
              )
          }
          .unsafeToFuture()
      }
    }

    "sending transactions (eth_sendTransaction)" should {
      "send a transaction (given sender address and using an unlocked account)" in {
        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))
        val unlockAccountRequest = UnlockAccountRequest(address, passphrase, None)

        implicit val current: CurrentBranch.Signal[IO] = Signal.constant(CurrentBranch(genesisHeader, obftHeader))
        val personalController =
          new PersonalController(
            keyStore,
            NewTransactionListenerStub(),
            blockchainConfig,
            new NonceServiceStub[IO](nonce),
            signedTxValidator,
            AccountServiceStub(Account(nonce, 2 * txValue))
          )

        personalController
          .unlockAccount(unlockAccountRequest)
          .flatMap { _ =>
            (signedTxValidator
              .validateForMempool(
                _: SignedTransaction,
                _: Account,
                _: BlockNumber,
                _: BigInt,
                _: BigInt
              )(
                _: BlockchainConfig
              ))
              .expects(*, *, *, *, *, *)
              .returning(Right(SignedTransactionValid))

            personalController
              .sendTransaction(txRequest)
              .map(_.toOption.get)
              .map(response => response shouldBe SendTransactionResponse(signedTx.hash))
          }
          .unsafeToFuture()
      }

      "fail to send a transaction when account is locked" in {
        personalController
          .sendTransaction(txRequest)
          .map {
            case Right(_)    => fail("JsonRpcError.executionError(AccountLocked) expected")
            case Left(error) => error shouldBe JsonRpcError.executionError(AccountLocked)
          }
          .unsafeToFuture()
      }

      "fail to send a transaction when Gas Limit < Intrinsic Gas" in {
        val personalController =
          new PersonalController(
            keyStore,
            NewTransactionListenerStub(),
            blockchainConfig,
            new NonceServiceStub[IO](nonce),
            signedTxValidator,
            AccountServiceStub(Account(nonce, 2 * txValue))
          )

        (keyStore.unlockAccount _).expects(address, passphraseAsString).returning(IO(Right(wallet)))
        val unlockAccountRequest = UnlockAccountRequest(address, passphrase, None)

        personalController
          .unlockAccount(unlockAccountRequest)
          .flatMap { _ =>
            setMocksToFailWithTransactionNotEnoughGasForIntrinsicError()

            personalController
              .sendTransaction(txRequest)
              .map {
                case Right(_) => fail("TransactionNotEnoughGasForIntrinsicError(1000, 76580) expected")
                case Left(error) =>
                  error shouldBe JsonRpcError.InvalidParams(
                    "Insufficient transaction gas limit: Transaction gas limit (1000) < transaction intrinsic gas (76580)."
                  )
              }
          }
          .unsafeToFuture()
      }
    }

    "personal_sign " should {
      "sign a message when correct passphrase is sent" in {

        (keyStore.unlockAccount _)
          .expects(address, passphraseAsString)
          .returning(IO(Right(wallet)))

        val message = Hex.decodeUnsafe("deadbeaf")

        val r = Hex.decodeUnsafe("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2")
        val s = Hex.decodeUnsafe("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00")
        val v = Hex.decodeAsArrayUnsafe("1b").last

        personalController
          .sign(message, address, Some(passphrase))
          .map(_.toOption.get)
          .map { response =>
            response shouldBe ECDSASignature(r, s, v)
          }
          .unsafeToFuture()
          .flatMap { _ =>
            personalController
              .sendTransaction(txRequest)
              .map {
                case Right(_) => fail("InvalidPassphrase expected")
                // Account should still be locked after calling sign with passphrase
                case Left(error) => error shouldBe JsonRpcError.executionError(AccountLocked)
              }
              .unsafeToFuture()
          }
      }

      "sign a message using an unlocked account" in {

        (keyStore.unlockAccount _)
          .expects(address, passphraseAsString)
          .returning(IO(Right(wallet)))

        val message = ByteString(Hex.decodeAsArrayUnsafe("deadbeaf"))

        val r = ByteString(Hex.decodeAsArrayUnsafe("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
        val s = ByteString(Hex.decodeAsArrayUnsafe("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
        val v = ByteString(Hex.decodeAsArrayUnsafe("1b")).last

        personalController
          .unlockAccount(UnlockAccountRequest(address, passphrase, None))
          .unsafeToFuture()
          .flatMap { _ =>
            personalController
              .sign(message, address, None)
              .map(_.toOption.get)
              .map { response =>
                response shouldBe ECDSASignature(r, s, v)
              }
              .unsafeToFuture()
          }
      }

      "return an error if signing a message using a locked account" in {

        val message = ByteString(Hex.decodeAsArrayUnsafe("deadbeaf"))

        val address = Address(Hex.decodeUnsafe("aa6826f00d01fe4085f0c3dd12778e206ce4e7ac"))

        personalController
          .sign(message, address, None)
          .map {
            case Right(_)    => fail("InvalidPassphrase expected")
            case Left(error) => error shouldBe JsonRpcError.executionError(AccountLocked)
          }
          .unsafeToFuture()
      }

      "return an error when signing a message if passphrase is wrong" in {

        val wrongPassphase = Passphrase("wrongPassphrase")

        (keyStore.unlockAccount _)
          .expects(address, "wrongPassphrase")
          .returning(IO(Left(DecryptionFailed)))

        val message = ByteString(Hex.decodeUnsafe("deadbeaf"))

        personalController
          .sign(message, address, Some(wrongPassphase))
          .map {
            case Right(_)    => fail("InvalidPassphrase expected")
            case Left(error) => error shouldBe JsonRpcError.InvalidPassphrase
          }
          .unsafeToFuture()
      }

      "return an error when signing if nonexistent address is sent" in {

        (keyStore.unlockAccount _)
          .expects(address, passphraseAsString)
          .returning(IO(Left(KeyStore.KeyNotFound)))

        val message = ByteString(Hex.decodeUnsafe("deadbeaf"))

        personalController
          .sign(message, address, Some(passphrase))
          .map {
            case Right(_)    => fail("InvalidPassphrase expected")
            case Left(error) => error shouldBe JsonRpcError.KeyNotFound
          }
          .unsafeToFuture()
      }
    }

    "personal_importRawKey" should {
      "import private keys" in {
        (keyStore.importPrivateKey _).expects(prvKey, passphraseAsString).returning(IO(Right(address)))

        personalController
          .importRawKey(RawPrivateKey(prvKey.bytes), passphrase)
          .map(_.toOption.get)
          .map { response =>
            response shouldEqual address
          }
          .unsafeToFuture()
      }

      "return an error when trying to import an invalid key" in {
        val invalidKey = prvKey.bytes.tail
        personalController
          .importRawKey(RawPrivateKey(invalidKey), passphrase)
          .map {
            case Right(_)    => fail("InvalidKey expected")
            case Left(error) => error shouldBe JsonRpcError.InvalidKey
          }
          .unsafeToFuture()
      }

      "return an error when importing a duplicated key" in {
        (keyStore.importPrivateKey _)
          .expects(prvKey, passphraseAsString)
          .returning(IO(Left(KeyStore.DuplicateKeySaved)))

        personalController
          .importRawKey(RawPrivateKey(prvKey.bytes), passphrase)
          .map {
            case Right(_)    => fail("Account should already exist")
            case Left(error) => error shouldBe LogicError("Account already exists")
          }
          .unsafeToFuture()
      }
    }

    "personal_ecRecover" should {
      "recover address from signed message" in {
        val sigAddress = Address(ByteString(Hex.decodeUnsafe("12c2a3b877289050FBcfADC1D252842CA742BE81")))

        val message = ByteString(Hex.decodeUnsafe("deadbeaf"))

        val r: ByteString =
          ByteString(Hex.decodeUnsafe("117b8d5b518dc428d97e5e0c6f870ad90e561c97de8fe6cad6382a7e82134e61"))
        val s: ByteString =
          ByteString(Hex.decodeUnsafe("396d881ef1f8bc606ef94b74b83d76953b61f1bcf55c002ef12dd0348edff24b"))
        val v: Byte = ByteString(Hex.decodeAsArrayUnsafe("1b")).last

        personalController
          .ecRecover(message, ECDSASignature(r, s, v))
          .map(_.toOption.get)
          .map { response =>
            response shouldEqual sigAddress
          }
          .unsafeToFuture()
      }

      "allow to sign and recover the same message" in {
        (keyStore.unlockAccount _)
          .expects(address, passphraseAsString)
          .returning(IO(Right(wallet)))

        val message = ByteString(Hex.decodeAsArrayUnsafe("deadbeaf"))

        (for {
          signature <- personalController
                         .sign(message, address, Some(passphrase))
                         .map(_.toOption.get)
          response <- personalController
                        .ecRecover(message, signature)
                        .map(_.toOption.get)
        } yield response shouldBe address).unsafeToFuture()
      }
    }
  }

  private def setMocksToFailWithTransactionNotEnoughGasForIntrinsicError() =
    (signedTxValidator
      .validateForMempool(_: SignedTransaction, _: Account, _: BlockNumber, _: BigInt, _: BigInt)(
        _: BlockchainConfig
      ))
      .expects(*, *, *, *, *, *)
      .returning(Left(TransactionNotEnoughGasForIntrinsicError(1000, 76580)))
}

object PersonalControllerFixture {
  val passphrase: Passphrase     = Passphrase("new!Super'p@ssphr@se")
  val passphraseAsString: String = passphrase.value
  val address: Address           = Address(Hex.decodeAsArrayUnsafe("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
  val prvKey: ECDSA.PrivateKey =
    ECDSA.PrivateKey.fromHexUnsafe("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f")
  val wallet: Wallet                    = Wallet(address, prvKey)
  val chainId: BlockchainConfig.ChainId = TestCoreConfigs.chainId

  val nonce: Nonce = Nonce(7)
  val txValue      = 128000
  val txRequest: TransactionRequest =
    TransactionRequest(from = address, to = Some(Address(42)), value = Some(Token(txValue)))

  val signedTx: SignedTransaction = wallet.signTx(txRequest.toTransaction(nonce), Some(chainId))

  val dummySig: ECDSASignature = {
    val signatureRandom =
      Hex.decodeAsArrayUnsafe("f3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401")
    val signature = Hex.decodeAsArrayUnsafe("53629a403579f5ce57bcbefba2616b1c6156d308ddcd37372c94943fdabeda97")
    val pointSign = 28

    ECDSASignature(BigInt(1, signatureRandom), BigInt(1, signature), pointSign.toByte)
  }

  val obftHeader: ObftHeader = ObftHeader(
    parentHash = BlockHash(Hex.decodeUnsafe("8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71")),
    beneficiary = Address("df7d7e053933b5cc24372f878c90e62dadad5d42"),
    stateRoot = Hex.decodeUnsafe("087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67"),
    transactionsRoot = Hex.decodeUnsafe("8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac"),
    receiptsRoot = Hex.decodeUnsafe("8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d"),
    logsBloom = Hex.decodeUnsafe("0" * 512),
    number = BlockNumber(1234),
    slotNumber = Slot(1000),
    gasLimit = 4699996,
    gasUsed = 84000,
    unixTimestamp = 1486131165.millisToTs,
    publicSigningKey = ECDSA.PublicKey.Zero,
    signature = dummySig
  )
}
