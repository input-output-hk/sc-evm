package io.iohk.scevm.rpc.armadillo.personal

import io.iohk.armadillo.JsonRpcError.NoData
import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, errorNoData}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.scevm.domain.{Address, TransactionHash}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.PersonalController.{Passphrase, RawPrivateKey}
import io.iohk.scevm.rpc.domain.TransactionRequest

import java.time.Duration

object PersonalApi extends CommonApi {
  private val transactionRequestParam = input[TransactionRequest]("TransactionRequest")
  private val passphraseParam         = input[Passphrase]("passphrase")
  private val optionalPassphraseParam = input[Option[Passphrase]]("passphrase")
  private val durationParam           = input[Option[Duration]]("duration")
  private val addressParam            = input[Address]("address")
  private val messageParam            = input[ByteString]("message")
  private val privateKeyParam         = input[RawPrivateKey]("private key")
  private val signatureParam          = input[ECDSASignature]("signature")

  // scalastyle:off line.size.limit
  val personal_newAccount: JsonRpcEndpoint[Passphrase, NoData, Address] =
    baseEndpoint(
      "personal_newAccount",
      """Creates a new account by generating a new address using a random private key encrypted with the given passphrase.
        |The key is then saved in the key store, and the address of the account is returned.""".stripMargin
    )
      .in(passphraseParam)
      .out(output[Address]("address"))
      .errorOut(errorNoData)

  val personal_listAccounts: JsonRpcEndpoint[Unit, NoData, List[Address]] =
    baseEndpoint("personal_listAccounts", "Returns all the account addresses that are saved in the key store.")
      .out(output[List[Address]]("accounts"))
      .errorOut(errorNoData)

  val personal_unlockAccount: JsonRpcEndpoint[(Address, Passphrase, Option[Duration]), NoData, Boolean] =
    baseEndpoint(
      "personal_unlockAccount",
      """Unlocks the given account so it can be used to send transactions.
        |The optional duration can be used to define the period of time (in seconds) during which the account is unlocked (0 for an infinite duration).""".stripMargin
    )
      .in(addressParam.and(passphraseParam).and(durationParam))
      .out(output[Boolean]("result"))
      .errorOut(errorNoData)

  val personal_lockAccount: JsonRpcEndpoint[Address, NoData, Boolean] =
    baseEndpoint("personal_lockAccount", "Removes the possibility of sending transactions using the given account.")
      .in(addressParam)
      .out(output[Boolean]("result"))
      .errorOut(errorNoData)

  val personal_sendTransaction: JsonRpcEndpoint[(TransactionRequest, Passphrase), NoData, TransactionHash] =
    baseEndpoint(
      "personal_sendTransaction",
      "Temporarily unlocks the account by using the given passphrase and sends the given transaction to the blockchain."
    )
      .in(transactionRequestParam.and(passphraseParam))
      .out(output[TransactionHash]("transactionHash"))
      .errorOut(errorNoData)

  val personal_sign: JsonRpcEndpoint[(ByteString, Address, Option[Passphrase]), NoData, ECDSASignature] =
    baseEndpoint(
      "personal_sign",
      """Returns an ECSDA signature generated using the given message and the private key associated with the given address.
        |The formula `sign(keccak256(\"\\x19Ethereum Signed Message:\\n\" + len(message) + message))` is used to generate the signature.
        |Note that this operation will fail if the account is locked. If the account is not already unlocked, the passphrase parameter can be used to temporarily unlock the account.""".stripMargin
    )
      .in(
        messageParam
          .and(addressParam)
          .and(optionalPassphraseParam)
      )
      .out(output[ECDSASignature]("ECDSASignature"))
      .errorOut(errorNoData)

  val personal_importRawKey: JsonRpcEndpoint[(RawPrivateKey, Passphrase), NoData, Address] =
    baseEndpoint(
      "personal_importRawKey",
      """Encrypts the given private key with the provided passphrase and stores it in the key store.
        |Then, creates a new account with the encrypted key and returns the associated address""".stripMargin
    )
      .in(privateKeyParam.and(passphraseParam))
      .out(output[Address]("address"))
      .errorOut(errorNoData)

  val personal_ecRecover: JsonRpcEndpoint[(ByteString, ECDSASignature), NoData, Address] =
    baseEndpoint(
      "personal_ecRecover",
      """Retrieves the private key associated to the given signature and message, and returns the address of the associated account
        |The message should be the same as the one provided when the signature was generated using `personal_sign` endpoint.""".stripMargin
    )
      .in(messageParam.and(signatureParam))
      .out(output[Address]("address"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
