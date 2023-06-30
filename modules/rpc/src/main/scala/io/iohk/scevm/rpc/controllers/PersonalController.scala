package io.iohk.scevm.rpc.controllers

import cats.Show
import cats.data.{EitherT, NonEmptyList}
import cats.effect.IO
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{Address, SidechainPrivateKey, SidechainSignature, SignedTransaction, TransactionHash}
import io.iohk.scevm.exec.validators.{SignedTransactionError, SignedTransactionValid, SignedTransactionValidator}
import io.iohk.scevm.keystore.{ExpiringMap, KeyStore}
import io.iohk.scevm.network.NewTransactionsListener
import io.iohk.scevm.network.TransactionsImport.TransactionsFromRpc
import io.iohk.scevm.rpc.domain.JsonRpcError.{AccountLocked, InvalidParams, LogicError}
import io.iohk.scevm.rpc.domain.{JsonRpcError, TransactionRequest}
import io.iohk.scevm.rpc.{AccountService, NonceService, ServiceResponse}
import io.iohk.scevm.serialization.Newtype
import io.iohk.scevm.utils.Logger
import io.iohk.scevm.wallet.Wallet

import java.time.Duration
import scala.language.implicitConversions

object PersonalController {

  private val redacted: String = "***"

  @newtype final case class Passphrase(value: String)

  object Passphrase {
    implicit val show: Show[Passphrase] = Show.show(_ => redacted)

    implicit val valueClass: Newtype[Passphrase, String] = Newtype[Passphrase, String](Passphrase.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[Passphrase] = new Manifest[Passphrase] {
      override def runtimeClass: Class[_] = Passphrase.getClass
    }

    override def toString: String = redacted
  }

  @newtype final case class RawPrivateKey(value: ByteString)

  object RawPrivateKey {
    implicit val show: Show[RawPrivateKey] = Show.show(_ => redacted)

    implicit val valueClass: Newtype[RawPrivateKey, ByteString] =
      Newtype[RawPrivateKey, ByteString](RawPrivateKey.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[RawPrivateKey] = new Manifest[RawPrivateKey] {
      override def runtimeClass: Class[_] = RawPrivateKey.getClass
    }

    override def toString: String = redacted
  }

  final case class SendTransactionRequest(tx: TransactionRequest)

  final case class SendTransactionResponse(txHash: TransactionHash)

  final case class UnlockAccountRequest(address: Address, passphrase: Passphrase, duration: Option[Duration])

  private val DefaultUnlockTime = 300

  def signMessage(message: ByteString, prvKey: SidechainPrivateKey): SidechainSignature =
    prvKey.sign(ByteString(getMessageToSign(message)))

  private def getMessageToSign(message: ByteString): Array[Byte] = {
    val prefixed: Array[Byte] =
      0x19.toByte +:
        s"Ethereum Signed Message:\n${message.length}".getBytes ++:
        message.toArray[Byte]

    crypto.kec256(prefixed)
  }
}

class PersonalController(
    keyStore: KeyStore[IO],
    transactionsImportService: NewTransactionsListener[IO],
    blockchainConfig: BlockchainConfig,
    nonceService: NonceService[IO],
    signedTxValidator: SignedTransactionValidator,
    accountProvider: AccountService[IO]
)(implicit current: CurrentBranch.Signal[IO])
    extends Logger {

  import io.iohk.scevm.rpc.controllers.PersonalController._

  private[controllers] val unlockedWallets: ExpiringMap[Address, Wallet] =
    ExpiringMap.empty(Duration.ofSeconds(DefaultUnlockTime))

  def newAccount(passphrase: Passphrase): ServiceResponse[Address] =
    EitherT(keyStore.newAccount(passphrase.value))
      .leftMap(handleError)
      .value

  def listAccounts(): ServiceResponse[List[Address]] =
    EitherT(keyStore.listAccounts())
      .leftMap(handleError)
      .value

  def unlockAccount(request: UnlockAccountRequest): ServiceResponse[Boolean] =
    unlockAccount(request.address, request.passphrase, request.duration)

  def unlockAccount(
      address: Address,
      passphrase: Passphrase,
      maybeDuration: Option[Duration]
  ): ServiceResponse[Boolean] =
    EitherT(keyStore.unlockAccount(address, passphrase.value))
      .leftMap(handleError)
      .map { wallet =>
        maybeDuration.fold(unlockedWallets.add(address, wallet))(duration =>
          if (duration.isZero)
            unlockedWallets.addForever(address, wallet)
          else
            unlockedWallets.add(address, wallet, duration)
        )

        true
      }
      .value

  def lockAccount(address: Address): ServiceResponse[Boolean] = IO.delay {
    if (unlockedWallets.underlying.contains(address))
      unlockedWallets.remove(address)

    Right(true)
  }

  def sendTransaction(req: SendTransactionRequest): ServiceResponse[SendTransactionResponse] =
    sendTransaction(req.tx)

  def sendTransaction(tx: TransactionRequest): ServiceResponse[SendTransactionResponse] =
    unlockedWallets
      .get(tx.from) match {
      case Some(wallet) =>
        addTransactionToPool(tx, wallet)
          .map(
            _.map(txHash => SendTransactionResponse(txHash)).left.map(error =>
              JsonRpcError.InvalidParams(error.toString)
            )
          )

      case None => IO.pure(Left(JsonRpcError.executionError(AccountLocked)))
    }

  def sendTransaction(
      tx: TransactionRequest,
      passphrase: Passphrase
  ): ServiceResponse[TransactionHash] =
    EitherT(keyStore.unlockAccount(tx.from, passphrase.value))
      .leftMap(handleError(_))
      .flatMap { wallet =>
        EitherT(addTransactionToPool(tx, wallet))
          .leftMap(error => JsonRpcError.InvalidParams(error.toString))
      }
      .value

  def sign(message: ByteString, address: Address, passphrase: Option[Passphrase]): ServiceResponse[SidechainSignature] =
    for {
      accountWallet <- passphrase match {
                         case Some(pass) =>
                           EitherT(keyStore.unlockAccount(address, pass.value)).leftMap(handleError).value
                         case None =>
                           IO(unlockedWallets.get(address).toRight(JsonRpcError.executionError(AccountLocked)))
                       }

    } yield accountWallet
      .map { wallet =>
        signMessage(message, wallet.prvKey)
      }

  def importRawKey(prvKey: RawPrivateKey, passphrase: Passphrase): ServiceResponse[Address] =
    EitherT
      .fromEither[IO](SidechainPrivateKey.fromBytes(prvKey.value))
      .leftMap(_ => JsonRpcError.InvalidKey)
      .flatMap { key =>
        EitherT(keyStore.importPrivateKey(key, passphrase.value))
          .leftMap(handleError)
      }
      .value

  def ecRecover(message: ByteString, signature: SidechainSignature): ServiceResponse[Address] = IO.blocking {
    signature
      .publicKey(getMessageToSign(message))
      .map { publicKey =>
        Right(Address(crypto.kec256(publicKey)))
      }
      .getOrElse(Left(InvalidParams("unable to recover address")))
  }

  private def addTransactionToPool(
      request: TransactionRequest,
      wallet: Wallet
  ): IO[Either[SignedTransactionError, TransactionHash]] =
    for {
      nextTxNonce <- nonceService.getNextNonce(wallet.address)
      tx           = request.toTransaction(nextTxNonce)
      stx         <- IO.delay(wallet.signTx(tx, Some(blockchainConfig.chainId)))
      maybeValid  <- validateTransaction(stx, wallet.address)
      hashOrError <- maybeValid match {
                       case Right(_) =>
                         transactionsImportService
                           .importTransaction(TransactionsFromRpc(NonEmptyList.one(stx)))
                           .as(Right(stx.hash))
                       case Left(error) =>
                         IO.pure(Left(error))
                     }
    } yield hashOrError

  private def validateTransaction(
      signedTx: SignedTransaction,
      address: Address
  ): IO[Either[SignedTransactionError, SignedTransactionValid]] = for {
    bestHeader <- CurrentBranch.best[IO]
    account    <- accountProvider.getAccount(bestHeader, address)
    valid <- IO.pure(
               signedTxValidator.validateForMempool(
                 signedTx,
                 account,
                 bestHeader.number,
                 bestHeader.gasLimit,
                 accumGasUsed = BigInt(0)
               )(blockchainConfig)
             )
  } yield valid

  private val handleError: KeyStore.KeyStoreError => JsonRpcError = {
    case KeyStore.DecryptionFailed              => JsonRpcError.InvalidPassphrase
    case KeyStore.KeyNotFound                   => JsonRpcError.KeyNotFound
    case KeyStore.InvalidKeyFormat              => JsonRpcError.InvalidKey
    case KeyStore.PassphraseTooShort(minLength) => JsonRpcError.PassphraseTooShort(minLength)
    case KeyStore.IOError(msg)                  => LogicError(msg)
    case KeyStore.DuplicateKeySaved             => LogicError("Account already exists")
  }
}
