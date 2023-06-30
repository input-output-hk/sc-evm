package io.iohk.scevm.testnode

import cats.effect.Async
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.scevm.config.{BlockchainConfig, ForkBlockNumbers, KeyStoreConfig, StandaloneBlockchainConfig}
import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranch, CurrentBranchService}
import io.iohk.scevm.db.storage.{GetByNumberService, ObftStorageBuilder}
import io.iohk.scevm.domain._
import io.iohk.scevm.ledger.BlockImportService
import io.iohk.scevm.mpt.{EthereumUInt256Mpt, MerklePatriciaTrie}
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import io.iohk.scevm.rpc.{FilterConfig, ServiceResponseF}
import io.iohk.scevm.storage.execution.SlotBasedMempool
import io.iohk.scevm.testnode.TestJRpcController.ScEvmNodeProxy
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.{FiniteDurationToUnixTimestamp, LongToUnixTimestamp}

import scala.collection.View
import scala.concurrent.duration.DurationLong

class TestJRpcController[F[+_]: Async] private (
    scEvmNodeProxy: ScEvmNodeProxy[F],
    standaloneBlockchainConfig: StandaloneBlockchainConfig,
    keyStoreConfig: KeyStoreConfig,
    filterConfig: FilterConfig
) extends JsonMethodsImplicits {
  import TestJRpcController._
  type ServiceResponse[T] = ServiceResponseF[F, T]

  private var blockTimestamp: UnixTimestamp                        = 0.millisToTs
  private var accountHashWithAdresses: List[(ByteString, Address)] = List()
  private var preimageCache: Map[ByteString, UInt256]              = buildPreimages(Map.empty)

  implicit def currentBranch: CurrentBranch.Signal[F] = scEvmNodeProxy.currentBranchService.signal

  def setChainParams(chainParams: ChainParams): ServiceResponse[Boolean] = {

    val params      = chainParams
    val genesisData = toGenesisData(params.genesis, params.accounts)

    val coinbase = Address(params.genesis.author)
    accountHashWithAdresses = (coinbase :: genesisData.alloc.keys.toList)
      .map { address =>
        crypto.kec256(address.bytes) -> address
      }
      .sortBy(v => UInt256(v._1))
    preimageCache = buildPreimages(genesisData.alloc)

    scEvmNodeProxy
      .setNewNode(
        keyStoreConfig,
        buildNewConfig(params.blockchainParams, genesisData, coinbase),
        filterConfig
      )
      .as(true.asRight[JsonRpcError])
  }

  def mineBlocks(blockCount: BlockCount): ServiceResponse[Boolean] = {
    val tickTask: F[Unit] =
      scEvmNodeProxy
        .triggerTick(blockTimestamp)
        .map(_ => blockTimestamp = blockTimestamp.add(standaloneBlockchainConfig.slotDuration))

    Iterator
      .continually(tickTask)
      .take(blockCount.number)
      .to(List)
      .sequence
      .as(true.asRight[JsonRpcError])
  }

  val modifyTimestamp: Timestamp => ServiceResponse[Boolean] = {
    // We add a few milliseconds for the timestamp because SC EVM internally skip blocks that were already imported in
    // the past. The storage is reset when setting the chain params, but not on block rewind so it might happen to have
    // some blocks that are ignored by SC EVM, which makes some ETS tests fail.
    //
    // Adding a few ms makes sure that the hash is different and it won't affect the result of the TIMESTAMP opcode
    var counter: Long = 0
    (timestamp: Timestamp) =>
      Async[F]
        .delay {
          blockTimestamp = (timestamp.value.second + counter.millis).asTs
          counter = (counter + 1) % 1000
        }
        .as(true.asRight[JsonRpcError])
  }

  def rewindToBlock(blockNumber: BlockNumber): ServiceResponse[Boolean] = {
    val blockToRewindTo: F[Option[ObftHeader]] = for {
      bestHeader   <- CurrentBranch.best[F]
      blockHashOpt <- scEvmNodeProxy.getByNumberService.getHashByNumber(bestHeader.hash)(blockNumber)
      headerOpt <- blockHashOpt.fold(Option.empty[ObftHeader].pure[F]) {
                     scEvmNodeProxy.storage.blockchainStorage.getBlockHeader
                   }
    } yield headerOpt

    blockToRewindTo.flatMap {
      case Some(header) =>
        // Note that this works only because the k parameter is high enough in
        // the configuration so that stable is always genesis.
        // If this was not the case, we would have to rollback other storages.
        for {
          _ <- scEvmNodeProxy.currentBranchService.newBranch(ConsensusService.BetterBranch(Vector.empty, header))
          _ <- clearMempool()
        } yield Right(true)
      case None =>
        Async[F].pure(Left(JsonRpcError.BlockNotFound))
    }
  }

  def getAccountsInRange(params: AccountsInRangeParams): ServiceResponse[AccountsInRangeResponse] = {
    // This implementation works by keeping a list of know account from the genesis state
    // It might not cover all the cases as an account created inside a transaction won't be there.
    val addressHashAsUInt256 = UInt256(params.addressHash.byteString)

    val accountBatch: View[(ByteString, Address)] = accountHashWithAdresses.view
      .dropWhile { case (hash, _) => UInt256(hash) < addressHashAsUInt256 }
      .take(params.maxResults + 1)

    val addressMap: Map[ByteString, ByteString] = accountBatch
      .take(params.maxResults)
      .map { case (hash, address) => hash -> address.bytes }
      .to(Map)

    Async[F].pure(
      AccountsInRangeResponse(
        addressMap = addressMap,
        nextKey =
          if (accountBatch.size > params.maxResults)
            accountBatch.last._1
          else UInt256.Zero.bytes
      ).asRight[JsonRpcError]
    )

  }

  // FIXME this implementation only fetch the latest block
  // this should be enough for must use in ETS
  def storageRangeAt(params: StorageRangeParams): ServiceResponse[StorageRangeResponse] =
    CurrentBranch
      .best[F]
      .map { header =>
        val mpt = getAccountMpt(header)
        val storageRange =
          mpt
            .get(params.address)
            .map(extractStorage(params, _))
            .getOrElse(StorageRangeResponse(complete = false, Map.empty, None))
        storageRange.asRight[JsonRpcError]
      }

  private def extractStorage(params: StorageRangeParams, account: Account): StorageRangeResponse = {
    // This implementation might be improved. It is working for most tests in ETS but might be
    // not really efficient and would not work outside of a test context. We simply iterate over
    // every key known by the preimage cache.
    val (valueBatch, next) = preimageCache.toSeq
      .sortBy(v => UInt256(v._1))
      .view
      .dropWhile { case (hash, _) => UInt256(hash) < UInt256(params.begin.byteString) }
      .map { case (keyHash, keyValue) =>
        (keyHash.toArray, keyValue, getAccountStorageAt(account.storageRoot, keyValue))
      }
      .filterNot { case (_, _, storageValue) => storageValue == ByteString(0) }
      .take(params.maxResults + 1)
      .splitAt(params.maxResults)

    val storage = valueBatch
      .map { case (keyHash, keyValue, value) =>
        UInt256(keyHash).toHexString -> StorageEntry(keyValue.toHexString, UInt256(value).toHexString)
      }
      .to(Map)

    StorageRangeResponse(
      complete = next.isEmpty,
      storage = storage,
      nextKey = next.headOption.map { case (hash, _, _) => UInt256(hash).toHexString }
    )
  }

  private def buildNewConfig(
      blockchainParams: BlockchainParams,
      genesisData: ObftGenesisData,
      coinbase: Address
  ): BlockchainConfig = {
    val istanbulForkBlockNumber: BlockNumber = blockchainParams.istanbulForkBlock.getOrElse(neverOccurringBlock)

    // For block number which are not specified by retesteth, we try to align the number to another fork
    standaloneBlockchainConfig.copy(
      forkBlockNumbers = TestJRpcController.emptyForkBlocks.copy(
        homesteadBlockNumber = blockchainParams.homesteadForkBlock.getOrElse(neverOccurringBlock),
        eip150BlockNumber = blockchainParams.EIP150ForkBlock.getOrElse(neverOccurringBlock),
        byzantiumBlockNumber = blockchainParams.byzantiumForkBlock.getOrElse(neverOccurringBlock),
        constantinopleBlockNumber = blockchainParams.constantinopleForkBlock.getOrElse(neverOccurringBlock),
        petersburgBlockNumber = istanbulForkBlockNumber,
        istanbulBlockNumber = istanbulForkBlockNumber,
        berlinBlockNumber = blockchainParams.berlinForkBlock.getOrElse(neverOccurringBlock),
        londonBlockNumber = blockchainParams.londonForkBlock.getOrElse(neverOccurringBlock)
      ),
      genesisData = genesisData.copy(accountStartNonce = blockchainParams.accountStartNonce),
      monetaryPolicyConfig = standaloneBlockchainConfig.monetaryPolicyConfig.copy(rewardAddress = Some(coinbase))
    )
  }

  private def toGenesisData(genesisParams: GenesisParams, accounts: Map[ByteString, ObftGenesisAccount]) =
    ObftGenesisData(
      coinbase = Address(genesisParams.author),
      gasLimit = genesisParams.gasLimit,
      timestamp = (genesisParams.timestamp.toLong * 1000).millisToTs,
      alloc = accounts.map { case (addr, acc) =>
        Address(addr) -> acc
      },
      Nonce.Zero
    )

  private def buildPreimages(alloc: Map[Address, ObftGenesisAccount]): Map[ByteString, UInt256] = {
    val preimageCacheFromGenesis =
      alloc.values
        .map(_.storage)
        .flatMap(_.keys)
        .map(storageKey => (crypto.kec256(storageKey.bytes), storageKey))

    defaultPreimageCache ++ preimageCacheFromGenesis.toMap
  }

  protected def getAccountMpt(header: ObftHeader): MerklePatriciaTrie[Address, Account] = {
    val storage = scEvmNodeProxy.storage.stateStorage.archivingStorage
    MerklePatriciaTrie[Address, Account](
      rootHash = header.stateRoot.toArray,
      source = storage
    )
  }
  private def getAccountStorageAt(
      rootHash: ByteString,
      position: BigInt
  ): ByteString = {
    val storage = scEvmNodeProxy.storage.stateStorage.archivingStorage
    val mpt     = EthereumUInt256Mpt.storageMpt(rootHash, storage)

    val bigIntValue    = mpt.get(position).getOrElse(BigInt(0))
    val byteArrayValue = bigIntValue.toByteArray

    // BigInt.toArray actually might return one more byte than necessary because it adds a sign bit, which in our case
    // will always be 0. This would add unwanted 0 bytes and might cause the value to be 33 byte long while an EVM
    // word is 32 byte long.
    if (bigIntValue != 0)
      ByteString(byteArrayValue.dropWhile(_ == 0))
    else
      ByteString(byteArrayValue)
  }

  private def clearMempool() = for {
    transactions <- scEvmNodeProxy.transactionPool.getAll
    _            <- scEvmNodeProxy.transactionPool.removeAll(transactions)
  } yield ()
}

object TestJRpcController {

  def apply[F[+_]: Async](
      scEvmNodeProxy: ScEvmNodeProxy[F],
      blockchainConfig: BlockchainConfig,
      keyStoreConfig: KeyStoreConfig,
      filterConfig: FilterConfig
  ): TestJRpcController[F] =
    blockchainConfig match {
      case standaloneBlockchainConfig: StandaloneBlockchainConfig =>
        new TestJRpcController(scEvmNodeProxy, standaloneBlockchainConfig, keyStoreConfig, filterConfig)
      case _ => throw new RuntimeException("Unsupported type of blockchain config")
    }

  final case class BlockCount(number: Int)

  val AddressHashLength = 32
  final case class AddressHash(byteString: ByteString) extends AnyVal

  final case class Timestamp(value: Long)

  final case class AccountsInRangeResponse(addressMap: Map[ByteString, ByteString], nextKey: ByteString)

  final case class StorageRangeResponse(
      complete: Boolean,
      storage: Map[String, StorageEntry],
      nextKey: Option[String]
  )

  final case class GenesisParams(
      author: ByteString,
      difficulty: String,
      extraData: ByteString,
      gasLimit: BigInt,
      parentHash: ByteString,
      timestamp: BigInt,
      nonce: ByteString,
      mixHash: ByteString
  )
  final case class BlockchainParams(
      EIP150ForkBlock: Option[BlockNumber],
      EIP158ForkBlock: Option[BlockNumber],
      accountStartNonce: Nonce,
      allowFutureBlocks: Boolean,
      blockReward: BigInt,
      byzantiumForkBlock: Option[BlockNumber],
      homesteadForkBlock: Option[BlockNumber],
      maximumExtraDataSize: BigInt,
      constantinopleForkBlock: Option[BlockNumber],
      istanbulForkBlock: Option[BlockNumber],
      berlinForkBlock: Option[BlockNumber],
      londonForkBlock: Option[BlockNumber]
  )

  sealed trait SealEngineType

  object SealEngineType {
    // Do not check `nonce` and `mixhash` field in blockHeaders
    final object NoProof extends SealEngineType
    // Do not check `nonce` and `mixhash` field in blockHeaders + Do not check mining reward (block + uncle headers)
    final object NoReward extends SealEngineType
  }

  final case class ChainParams(
      genesis: GenesisParams,
      blockchainParams: BlockchainParams,
      sealEngine: SealEngineType,
      accounts: Map[ByteString, ObftGenesisAccount]
  )

  final case class AccountsInRangeParams(
      blockHashOrNumber: Either[BlockNumber, BlockHash],
      txIndex: TransactionIndex,
      addressHash: AddressHash,
      maxResults: Int
  )

  final case class StorageRangeParams(
      blockHashOrNumber: Either[BlockNumber, BlockHash],
      txIndex: TransactionIndex,
      address: Address,
      begin: AddressHash,
      maxResults: Int
  )

  final case class StorageEntry(key: String, value: String)

  private val neverOccurringBlock = BlockNumber(Long.MaxValue)
  private val emptyForkBlocks: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = neverOccurringBlock,
    homesteadBlockNumber = neverOccurringBlock,
    eip150BlockNumber = neverOccurringBlock,
    spuriousDragonBlockNumber = neverOccurringBlock,
    byzantiumBlockNumber = neverOccurringBlock,
    constantinopleBlockNumber = neverOccurringBlock,
    petersburgBlockNumber = neverOccurringBlock,
    berlinBlockNumber = neverOccurringBlock,
    londonBlockNumber = neverOccurringBlock,
    istanbulBlockNumber = neverOccurringBlock
  )

  private val defaultPreimageCache: Map[ByteString, UInt256] =
    Iterator.iterate(UInt256.Zero)(_ + 1).take(10).map(v => (crypto.kec256(v.bytes), v)).toMap

  /** This trait gives the function expected by the [TestJRpcController] to be able to interact
    * with the inner SC EVM node
    */
  trait ScEvmNodeProxy[F[_]] {
    def setNewNode(
        keyStoreConfig: KeyStoreConfig,
        blockchainConfig: BlockchainConfig,
        filterConfig: FilterConfig
    ): F[Unit]
    def triggerTick(timestamp: UnixTimestamp): F[Unit]
    def currentBranchService: CurrentBranchService[F]
    def getByNumberService: GetByNumberService[F]
    def consensusService: ConsensusService[F]
    def obftImportService: BlockImportService[F]
    def transactionPool: SlotBasedMempool[F, SignedTransaction]
    def storage: ObftStorageBuilder[F]
    def addStorageModification(address: Address, memorySlot: MemorySlot, value: Token): F[Unit]
  }
}
