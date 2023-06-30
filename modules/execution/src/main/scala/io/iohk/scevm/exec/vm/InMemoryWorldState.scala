package io.iohk.scevm.exec.vm

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.db.storage.EvmCodeStorage
import io.iohk.scevm.db.storage.EvmCodeStorageImpl.Code
import io.iohk.scevm.domain._
import io.iohk.scevm.mpt.{EthereumUInt256Mpt, MerklePatriciaTrie, MptStorage}
import io.iohk.scevm.utils.InMemorySimpleMapProxy

object InMemoryWorldState {
  implicit val show: Show[InMemoryWorldState] = Show.show(_ => "InMemoryWorldState(...)")

  import Account._

  def apply(
      evmCodeStorage: EvmCodeStorage,
      mptStorage: MptStorage,
      getBlockHashByNumber: (BlockHash, BlockNumber) => Option[BlockHash],
      accountStartNonce: Nonce,
      stateRootHash: ByteString,
      noEmptyAccounts: Boolean,
      blockContext: BlockContext
  ): InMemoryWorldState = {
    val accountsStateTrieProxy = createProxiedAccountsStateTrie(mptStorage, stateRootHash)
    new InMemoryWorldState(
      stateStorage = mptStorage,
      accountsStateTrie = accountsStateTrieProxy,
      contractStorages = Map.empty,
      evmCodeStorage = evmCodeStorage,
      accountCodes = Map.empty,
      getBlockByNumber = getBlockHashByNumber,
      accountStartNonce = accountStartNonce,
      touchedAccounts = Set.empty,
      noEmptyAccountsCond = noEmptyAccounts,
      blockContext = blockContext
    )
  }

  /** Update and effectfully persist the changes
    *
    * To do so, it:
    *   - Commits and persists code (to get account's code hashes)
    *   - Commits and persists contract storages (to get account's contract storage root)
    *   - Updates and persists state tree
    *
    * @param worldState with changes to persist
    * @return Updated world
    */
  def persistState(worldState: InMemoryWorldState): InMemoryWorldState = {
    def persistCode(worldState: InMemoryWorldState): InMemoryWorldState =
      worldState.accountCodes.foldLeft(worldState) { case (updatedWorldState, (address, code)) =>
        val codeHash = kec256(code)
        updatedWorldState.evmCodeStorage.put(codeHash, code)
        updatedWorldState.copyWith(
          accountsStateTrie = updatedWorldState.accountsStateTrie +
            (address -> updatedWorldState.getGuaranteedAccount(address).copy(codeHash = codeHash)),
          accountCodes = Map.empty
        )
      }

    def persistContractStorage(worldState: InMemoryWorldState): InMemoryWorldState =
      worldState.contractStorages.foldLeft(worldState) { case (updatedWorldState, (address, storageTrie)) =>
        val persistedStorage   = storageTrie.persist()
        val newStorageRootHash = persistedStorage.inner.getRootHash

        updatedWorldState.copyWith(
          contractStorages = updatedWorldState.contractStorages + (address -> persistedStorage),
          accountsStateTrie = updatedWorldState.accountsStateTrie +
            (address -> updatedWorldState
              .getGuaranteedAccount(address)
              .copy(storageRoot = ByteString(newStorageRootHash)))
        )
      }

    def persistAccountsStateTrie(worldState: InMemoryWorldState): InMemoryWorldState =
      worldState.copyWith(accountsStateTrie = worldState.accountsStateTrie.persist())

    (persistCode _).andThen(persistContractStorage).andThen(persistAccountsStateTrie)(worldState)
  }

  /** Returns an [[InMemorySimpleMapProxy]] of the accounts state trie "The world state (state), is a mapping
    * between Keccak 256-bit hashes of the addresses (160-bit identifiers) and account states (a data structure serialised as RLP [...]).
    * Though not stored on the blockchain, it is assumed that the implementation will maintain this mapping in a
    * modified Merkle Patricia tree [...])."
    *
    * * See [[http://paper.gavwood.com YP 4.1]]
    *
    * @param accountsStorage Accounts Storage where trie nodes are saved
    * @param stateRootHash   State trie root hash
    * @return Proxied Accounts State Trie
    */
  private def createProxiedAccountsStateTrie(
      accountsStorage: MptStorage,
      stateRootHash: ByteString
  ): InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]] =
    InMemorySimpleMapProxy.wrap[Address, Account, MerklePatriciaTrie[Address, Account]](
      MerklePatriciaTrie[Address, Account](
        stateRootHash.toArray[Byte],
        accountsStorage
      )(Address.hashedAddressEncoder, accountSerializer)
    )
}

class InMemoryworldStateStorage(
    val wrapped: InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]
) extends Storage[InMemoryworldStateStorage] {

  override def store(addr: BigInt, value: BigInt): InMemoryworldStateStorage = {
    val newWrapped =
      if (value == 0) wrapped - addr
      else wrapped + (addr -> value)
    new InMemoryworldStateStorage(newWrapped)
  }

  override def load(addr: BigInt): BigInt = wrapped.get(addr).getOrElse(0)
}

class InMemoryWorldState(
    // State MPT proxied nodes storage needed to construct the storage MPT when calling [[getStorage]].
    // Accounts state and accounts storage states are saved within the same storage
    val stateStorage: MptStorage,
    val accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]],
    // Contract Storage Proxies by Address
    val contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]],
    //It's easier to use the storage instead of the blockchain here (because of proxy wrapping). We might need to reconsider this
    val evmCodeStorage: EvmCodeStorage,
    // Account's code by Address
    val accountCodes: Map[Address, Code],
    val getBlockByNumber: (BlockHash, BlockNumber) => Option[BlockHash],
    override val accountStartNonce: Nonce,
    // touchedAccounts and noEmptyAccountsCond are introduced by EIP161 to track accounts touched during the transaction
    // execution. Touched account are only added to Set if noEmptyAccountsCond == true, otherwise all other operations
    // operate on empty set.
    val touchedAccounts: Set[Address],
    val noEmptyAccountsCond: Boolean,
    val blockContext: BlockContext
) extends WorldState[InMemoryWorldState, InMemoryworldStateStorage] {

  override def getAccount(address: Address): Option[Account] = accountsStateTrie.get(address)

  override def getEmptyAccount: Account = Account.empty(accountStartNonce)

  override def getGuaranteedAccount(address: Address): Account = super.getGuaranteedAccount(address)

  override def saveAccount(address: Address, account: Account): InMemoryWorldState =
    copyWith(accountsStateTrie = accountsStateTrie.put(address, account))

  override def deleteAccount(address: Address): InMemoryWorldState =
    copyWith(
      accountsStateTrie = accountsStateTrie.remove(address),
      contractStorages = contractStorages - address,
      accountCodes = accountCodes - address
    )

  override def getCode(address: Address): ByteString =
    accountCodes.getOrElse(
      address,
      getAccount(address).flatMap(account => evmCodeStorage.get(account.codeHash)).getOrElse(ByteString.empty)
    )

  override def getStorage(address: Address): InMemoryworldStateStorage =
    new InMemoryworldStateStorage(contractStorages.getOrElse(address, getStorageForAddress(address, stateStorage)))

  override def saveCode(address: Address, code: ByteString): InMemoryWorldState =
    copyWith(accountCodes = accountCodes + (address -> code))

  override def saveStorage(address: Address, storage: InMemoryworldStateStorage): InMemoryWorldState =
    copyWith(contractStorages = contractStorages + (address -> storage.wrapped))

  override def touchAccounts(addresses: Address*): InMemoryWorldState =
    if (noEmptyAccounts)
      copyWith(touchedAccounts = touchedAccounts ++ addresses.toSet)
    else
      this

  override def clearTouchedAccounts: InMemoryWorldState =
    copyWith(touchedAccounts = touchedAccounts.empty)

  override def noEmptyAccounts: Boolean = noEmptyAccountsCond

  override def keepPrecompileTouched(world: InMemoryWorldState): InMemoryWorldState =
    if (world.touchedAccounts.contains(ripmdContractAddress))
      copyWith(touchedAccounts = touchedAccounts + ripmdContractAddress)
    else
      this

  /** Returns world state root hash. This value is only updated after persist.
    */
  def stateRootHash: ByteString = ByteString(accountsStateTrie.inner.getRootHash)

  def increaseAccountBalance(address: Address, value: UInt256)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldState = {
    val account =
      this
        .getAccount(address)
        .getOrElse(Account.empty(blockchainConfig.genesisData.accountStartNonce))
        .increaseBalance(value)
    this.saveAccount(address, account)
  }

  private def getStorageForAddress(address: Address, stateStorage: MptStorage) = {
    val storageRoot = getAccount(address)
      .map(account => account.storageRoot)
      .getOrElse(Account.EmptyStorageRootHash)
    createProxiedContractStorageTrie(stateStorage, storageRoot)
  }

  private def copyWith(
      stateStorage: MptStorage = stateStorage,
      accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]] =
        accountsStateTrie,
      contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]] =
        contractStorages,
      accountCodes: Map[Address, Code] = accountCodes,
      touchedAccounts: Set[Address] = touchedAccounts,
      blockContext: BlockContext = blockContext
  ): InMemoryWorldState =
    new InMemoryWorldState(
      stateStorage,
      accountsStateTrie,
      contractStorages,
      evmCodeStorage,
      accountCodes,
      getBlockByNumber,
      accountStartNonce,
      touchedAccounts,
      noEmptyAccountsCond,
      blockContext
    )

  def modifyBlockContext(mod: BlockContext => BlockContext): InMemoryWorldState =
    copyWith(blockContext = mod(blockContext))

  override def getBlockHash(number: UInt256): Option[UInt256] =
    getBlockByNumber(blockContext.parentHash, BlockNumber(number)).map(h => UInt256(h.byteString))

  /** Returns an [[InMemorySimpleMapProxy]] of the contract storage, for `ethCompatibleStorage` defined as "trie as a map-ping from the Keccak
    * 256-bit hash of the 256-bit integer keys to the RLP-encoded256-bit integer values."
    * See [[http://paper.gavwood.com YP 4.1]]
    *
    * @param contractStorage Storage where trie nodes are saved
    * @param storageRoot     Trie root
    * @return Proxied Contract Storage Trie
    */
  private def createProxiedContractStorageTrie(
      contractStorage: MptStorage,
      storageRoot: ByteString
  ): InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]] = {
    val mpt = EthereumUInt256Mpt.storageMpt(storageRoot, contractStorage)

    InMemorySimpleMapProxy.wrap[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]](mpt)
  }
}
