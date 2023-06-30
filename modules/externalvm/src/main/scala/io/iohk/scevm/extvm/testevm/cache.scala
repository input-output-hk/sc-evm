package io.iohk.scevm.extvm.testevm

import io.iohk.bytes.ByteString
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.domain.{Account, Address, Nonce}
import io.iohk.scevm.extvm.Implicits._
import io.iohk.scevm.utils.Logger
import scalapb.UnknownFieldSet

import scala.collection.mutable

class AccountCache(messageHandler: MessageHandler) extends Logger {
  private val cache = mutable.Map[Address, Option[Account]]()

  // we don't the actual hash value, we only need to compare it to Account.EmptyCodeHash
  val nonEmptyCodeHash: ByteString = Account.EmptyStorageRootHash
  // storage hash is irrelevant in the VM
  val defaultStorageHash: ByteString = Account.EmptyStorageRootHash

  def getAccount(address: Address): Option[Account] =
    cache.getOrElse(
      address, {
        val getAccountMsg = msg.GetAccount(address.bytes)
        val query         = msg.VMQuery(query = msg.VMQuery.Query.GetAccount(getAccountMsg))
        messageHandler.sendMessage(query)

        val accountMsg = messageHandler.awaitMessage[msg.Account]
        log.debug("Server received msg: Account")

        if (accountMsg.nonce.isEmpty) {
          cache += address -> None
          None
        } else {
          val codeHash = if (accountMsg.codeEmpty) Account.EmptyCodeHash else nonEmptyCodeHash
          val account  = Account(Nonce(accountMsg.nonce), accountMsg.balance, defaultStorageHash, codeHash)
          cache += address -> Some(account)
          Some(account)
        }
      }
    )
}

class CodeCache(messageHandler: MessageHandler) extends Logger {
  private val cache = mutable.Map[Address, ByteString]()

  def getCode(address: Address): ByteString =
    cache.getOrElse(
      address, {
        val getCodeMsg = msg.GetCode(address.bytes)
        val query      = msg.VMQuery(query = msg.VMQuery.Query.GetCode(getCodeMsg))
        messageHandler.sendMessage(query)

        val codeMsg = messageHandler.awaitMessage[msg.Code]
        log.debug("Server received msg: Code")

        val code: ByteString = codeMsg.code
        cache += address -> code
        code
      }
    )
}

class BlockhashCache(messageHandler: MessageHandler) extends Logger {
  private val cache = mutable.Map[BigInt, Option[Hash]]()

  def getBlockhash(offset: BigInt): Option[Hash] = ???

}

class StorageCache(messageHandler: MessageHandler) extends Logger {
  private val cache = mutable.Map[(Address, BigInt), BigInt]()

  def getStorageData(address: Address, offset: BigInt): BigInt =
    cache.getOrElse(
      (address, offset), {
        val getStorageDataMsg = msg.GetStorageData(address, offset, UnknownFieldSet.empty)
        val query             = msg.VMQuery(query = msg.VMQuery.Query.GetStorageData(getStorageDataMsg))
        messageHandler.sendMessage(query)

        val storageData = messageHandler.awaitMessage[msg.StorageData]
        log.debug("Server received msg: StorageData")
        val value: BigInt = storageData.data

        cache += (address, offset) -> value
        value
      }
    )
}
