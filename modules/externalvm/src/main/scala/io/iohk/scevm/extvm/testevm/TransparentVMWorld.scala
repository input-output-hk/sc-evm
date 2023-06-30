package io.iohk.scevm.extvm.testevm

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Account, Address, Nonce, UInt256}
import io.iohk.scevm.exec.vm.WorldState

private[extvm] object TransparentVMWorld {
  def apply(accountStartNonce: Nonce, messageHandler: MessageHandler): TransparentVMWorld =
    TransparentVMWorld(
      accountStartNonce = accountStartNonce,
      accountCache = new AccountCache(messageHandler),
      storageCache = new StorageCache(messageHandler),
      codeCache = new CodeCache(messageHandler),
      blockhashCache = new BlockhashCache(messageHandler)
    )
}

final private[extvm] case class TransparentVMWorld(
    override val accountStartNonce: Nonce,
    accountCache: AccountCache,
    storageCache: StorageCache,
    codeCache: CodeCache,
    blockhashCache: BlockhashCache,
    accounts: Map[Address, Account] = Map(),
    storages: Map[Address, Storage] = Map(),
    codeRepo: Map[Address, ByteString] = Map(),
    touchedAccounts: Set[Address] = Set()
) extends WorldState[TransparentVMWorld, Storage] {

  override def getAccount(address: Address): Option[Account] =
    accounts.get(address).orElse(accountCache.getAccount(address))

  override def saveAccount(address: Address, account: Account): TransparentVMWorld =
    copy(accounts = accounts + (address -> account))

  override def deleteAccount(address: Address): TransparentVMWorld =
    copy(accounts = accounts - address)

  override def getEmptyAccount: Account = Account.empty(accountStartNonce)

  override def touchAccounts(addresses: Address*): TransparentVMWorld =
    copy(touchedAccounts = touchedAccounts ++ addresses)

  override def clearTouchedAccounts: TransparentVMWorld =
    copy(touchedAccounts = Set.empty)

  override def getCode(address: Address): ByteString =
    codeRepo.getOrElse(address, codeCache.getCode(address))

  override def getStorage(address: Address): Storage =
    storages.getOrElse(address, new Storage(address, Map.empty, storageCache))

  def getBlockHash(offset: BigInt): Option[Hash] =
    blockhashCache.getBlockhash(offset)

  def getBlockHash(number: UInt256): Option[UInt256] = getBlockHash(number).map(h => UInt256(h.toBigInt))

  override def saveCode(address: Address, code: ByteString): TransparentVMWorld =
    copy(codeRepo = codeRepo + (address -> code))

  override def saveStorage(address: Address, storage: Storage): TransparentVMWorld =
    copy(storages = storages + (address -> storage))

  override protected def noEmptyAccounts: Boolean = true

  override def keepPrecompileTouched(world: TransparentVMWorld): TransparentVMWorld =
    if (world.touchedAccounts.contains(ripmdContractAddress))
      world.copy(touchedAccounts = touchedAccounts + ripmdContractAddress)
    else
      world
}
