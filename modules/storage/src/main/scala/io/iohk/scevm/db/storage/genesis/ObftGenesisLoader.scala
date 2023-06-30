package io.iohk.scevm.db.storage.genesis

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.StateStorage.GenesisDataLoad
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorage, EvmCodeStorageImpl, NodeStorage, StateStorage}
import io.iohk.scevm.domain._
import io.iohk.scevm.mpt.{EthereumUInt256Mpt, MerklePatriciaTrie, MptStorage}

object ObftGenesisLoader {
  def load[F[_]: Sync](evmCodeStorage: EvmCodeStorage, stateStorage: StateStorage)(
      genesisData: ObftGenesisData
  ): F[ObftBlock] = {
    val storeEvmCode: F[Unit] =
      Sync[F].delay(
        genesisData.alloc
          .flatMap { case (_, account) => account.code }
          .foreach(code => evmCodeStorage.put(crypto.kec256(code), code))
      )

    for {
      _      <- storeEvmCode
      _      <- Sync[F].delay(stateStorage.forcePersist(GenesisDataLoad))
      genesis = buildGenesis(stateStorage)(genesisData)
    } yield genesis
  }

  /** Allows to get the genesis hash without actually saving it anywhere */
  def getGenesisHash[F[_]: Sync](genesisData: ObftGenesisData): F[BlockHash] = {
    val ephemStorage = EphemDataSource()
    load[F](new EvmCodeStorageImpl(ephemStorage), new ArchiveStateStorage(new NodeStorage(ephemStorage)))(
      genesisData
    ).map(_.hash)
  }

  private def buildGenesis(stateStorage: StateStorage)(genesisData: ObftGenesisData): ObftBlock = {
    val mptStorage       = stateStorage.archivingStorage
    val initialRootHash  = MerklePatriciaTrie.EmptyRootHash
    val stateMptRootHash = getGenesisStateRoot(genesisData, initialRootHash, mptStorage)
    val header           = buildHeader(genesisData, stateMptRootHash)
    ObftBlock(header, ObftBody.empty)
  }

  private def getGenesisStateRoot(
      genesisData: ObftGenesisData,
      initalRootHash: Array[Byte],
      storage: MptStorage
  ): Array[Byte] = {
    import io.iohk.scevm.serialization.Implicits.byteArraySerializable

    genesisData.alloc.zipWithIndex.foldLeft(initalRootHash) { case (rootHash, ((address, genesisAccount), _)) =>
      val mpt = MerklePatriciaTrie[Array[Byte], Account](rootHash, storage)

      val stateRoot = mpt
        .put(
          crypto.kec256(address.bytes.toArray),
          Account(
            nonce = genesisAccount.nonce.getOrElse(genesisData.accountStartNonce),
            balance = genesisAccount.balance,
            codeHash = genesisAccount.code.fold(Account.EmptyCodeHash)(codeValue => crypto.kec256(codeValue)),
            storageRoot = computeStorageRootHash(genesisAccount.storage, storage)
          )
        )
        .getRootHash
      stateRoot
    }
  }

  private def computeStorageRootHash(accountStorage: Map[UInt256, UInt256], mptStorage: MptStorage): ByteString = {
    val emptyTrie = EthereumUInt256Mpt.storageMpt(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      mptStorage
    )

    val storageTrie = accountStorage.foldLeft(emptyTrie) {
      case (trie, (_, UInt256.Zero)) => trie
      case (trie, (key, value))      => trie.put(key, value)
    }

    ByteString(storageTrie.getRootHash)
  }

  private def buildHeader(genesisData: ObftGenesisData, stateMptRootHash: Array[Byte]) = {
    def zeros(length: Int) = ByteString(List.fill[Byte](length)(0))

    val hashLength  = 32
    val bloomLength = 256

    val emptyTrieRootHash: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val emptyPubKey                   = ECDSA.PublicKey.Zero
    val emptySignature                = ECDSASignature(ByteString.empty, ByteString.empty, 0)

    ObftHeader(
      parentHash = BlockHash(zeros(hashLength)),
      number = BlockNumber(0),
      slotNumber = Slot(0),
      beneficiary = genesisData.coinbase,
      stateRoot = ByteString(stateMptRootHash),
      transactionsRoot = emptyTrieRootHash,
      receiptsRoot = emptyTrieRootHash,
      logsBloom = zeros(bloomLength),
      gasLimit = genesisData.gasLimit,
      gasUsed = 0,
      unixTimestamp = genesisData.timestamp,
      publicSigningKey = emptyPubKey,
      signature = emptySignature
    )
  }
}
