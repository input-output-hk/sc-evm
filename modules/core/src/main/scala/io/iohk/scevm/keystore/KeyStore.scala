package io.iohk.scevm.keystore

import cats._
import cats.data.EitherT
import cats.effect.Sync
import cats.effect.std.Random
import cats.implicits._
import io.iohk.ethereum.Crypto
import io.iohk.scevm.config.KeyStoreConfig
import io.iohk.scevm.domain.{Address, SidechainPrivateKey}
import io.iohk.scevm.keystore.KeyStore._
import io.iohk.scevm.wallet.Wallet

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}

trait KeyStore[F[_]] {
  def newAccount(passphrase: String): F[Either[KeyStoreError, Address]]

  def listAccounts(): F[Either[KeyStoreError, List[Address]]]

  def unlockAccount(address: Address, passphrase: String): F[Either[KeyStoreError, Wallet]]

  def importPrivateKey(key: SidechainPrivateKey, passphrase: String): F[Either[KeyStoreError, Address]]
}

object KeyStore {
  sealed trait KeyStoreError

  case object DecryptionFailed                        extends KeyStoreError
  case object KeyNotFound                             extends KeyStoreError
  case object InvalidKeyFormat                        extends KeyStoreError
  case object DuplicateKeySaved                       extends KeyStoreError
  final case class PassphraseTooShort(minLength: Int) extends KeyStoreError
  final case class IOError(msg: String)               extends KeyStoreError

  // scalastyle:off method.length
  def create[F[_]: Sync: Random: Crypto](
      keyStoreConfig: KeyStoreConfig
  ): F[KeyStore[F]] = {
    def init(): F[Unit] = for {
      dir <- Sync[F].delay(new File(keyStoreConfig.keyStoreDir))
      _   <- Sync[F].blocking(dir.isDirectory || dir.mkdirs())
    } yield ()

    init() >> (new KeyStore[F] {

      override def newAccount(passphrase: String): F[Either[KeyStoreError, Address]] = (for {
        _       <- EitherT.fromEither[F](validateNewPassPhrase(passphrase))
        address <- EitherT(saveNewAccount(passphrase))
      } yield address).value

      private def saveNewAccount(passphrase: String): F[Either[KeyStoreError, Address]] = for {
        (prvKey, _) <- Crypto[F].generateKeyPair()
        encKey      <- EncryptedKey.create[F](prvKey, passphrase)
        res         <- save(encKey).map(_.as(encKey.address))
      } yield res

      private def validateNewPassPhrase(passPhrase: String): Either[KeyStoreError, Boolean] = {
        val trimmedPassphraseLength = passPhrase.trim.length

        val isValid =
          trimmedPassphraseLength >= keyStoreConfig.minimalPassphraseLength ||
            (keyStoreConfig.allowNoPassphrase && trimmedPassphraseLength == 0)

        if (isValid)
          Right(true)
        else
          Left(PassphraseTooShort(keyStoreConfig.minimalPassphraseLength))
      }

      private def save(encKey: EncryptedKey): F[Either[KeyStoreError, Boolean]] = {
        val json                                = EncryptedKeyJsonCodec.toJson(encKey)
        val name                                = fileName(encKey)
        val path                                = Paths.get(keyStoreConfig.keyStoreDir, name)
        val err: Either[KeyStoreError, Boolean] = Either.left(DuplicateKeySaved)

        (for {
          alreadyInKeyStore <- EitherT(containsAccount(encKey))
          res <- if (alreadyInKeyStore) EitherT.fromEither[F](err)
                 else
                   EitherT(
                     Sync[F].delay { Files.write(path, json.getBytes(StandardCharsets.UTF_8)); true }.attempt
                   ).leftMap(ioError)
        } yield res).value
      }

      private def load(address: Address): F[Either[KeyStoreError, EncryptedKey]] =
        (for {
          filename <- EitherT(findKeyFileName(address))
          key      <- EitherT(load(filename))
        } yield key).value

      private def load(path: String): F[Either[KeyStoreError, EncryptedKey]] =
        (for {
          json <-
            EitherT(
              Sync[F]
                .delay(
                  new String(Files.readAllBytes(Paths.get(keyStoreConfig.keyStoreDir, path)), StandardCharsets.UTF_8)
                )
                .attempt
            ).leftMap(ioError)
          key <- EitherT
                   .fromEither[F](
                     EncryptedKeyJsonCodec
                       .fromJson(json)
                       .filterOrElse(k => path.endsWith(k.address.toUnprefixedString), InvalidKeyFormat: KeyStoreError)
                   )
                   .leftMap(_ => InvalidKeyFormat: KeyStoreError)
        } yield key).value

      private def checkDirExists(name: String): F[File] = for {
        dir <- Sync[F].delay(new File(name))
        _ <- Monad[F].ifM(Sync[F].blocking(!dir.exists || !dir.isDirectory))(
               Sync[F].raiseError(new Throwable(s"Could not read ${keyStoreConfig.keyStoreDir}")),
               ().pure[F]
             )
      } yield dir

      private def listFiles(): F[Either[KeyStoreError, List[String]]] = for {
        dir <- checkDirExists(keyStoreConfig.keyStoreDir).attempt
      } yield (dir.map(_.listFiles().toList.map(_.getName)).leftMap(ioError))

      private def ioError(ex: Throwable): KeyStoreError = IOError(ex.toString)

      private def fileName(encKey: EncryptedKey) = {
        val dateStr = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME).replace(':', '-')
        val addrStr = encKey.address.toUnprefixedString
        s"UTC--$dateStr--$addrStr"
      }

      private def containsAccount(encKey: EncryptedKey): F[Either[KeyStoreError, Boolean]] = load(encKey.address).map {
        case Right(_)          => Right(true)
        case Left(KeyNotFound) => Right(false)
        case Left(err)         => Left(err)
      }

      private def findKeyFileName(address: Address): F[Either[KeyStoreError, String]] = for {
        files <- listFiles()
      } yield files.flatMap(_.find(_.endsWith(address.toUnprefixedString)).toRight(KeyNotFound))

      override def listAccounts(): F[Either[KeyStoreError, List[Address]]] =
        (for {
          files <- EitherT(listFiles())
          // given the date and filename formats sorting by date is equivalent to sorting by name
          sorted  = files.sorted
          loaded <- sorted.traverse(s => EitherT(load(s)))
        } yield loaded.map(_.address)).value

      override def unlockAccount(address: Address, passphrase: String): F[Either[KeyStoreError, Wallet]] =
        (for {
          encryptedKey <- EitherT(load(address))
          decryptedKey <-
            EitherT.fromEither[F](encryptedKey.decrypt(passphrase).left.map[KeyStoreError](_ => DecryptionFailed))
        } yield Wallet(address, decryptedKey)).value

      override def importPrivateKey(
          prvKey: SidechainPrivateKey,
          passphrase: String
      ): F[Either[KeyStoreError, Address]] =
        for {
          _       <- validateNewPassPhrase(passphrase).pure[F]
          encKey  <- EncryptedKey.create[F](prvKey, passphrase)
          saveRes <- save(encKey)
        } yield saveRes.map(_ => encKey.address)

    }).pure[F]
  }
  // scalastyle:on method.length
}
