package io.iohk.scevm.sidechain

import cats.data.StateT
import cats.effect.IO
import cats.effect.kernel.Resource
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.{WorldStateBuilder, WorldStateBuilderImpl}
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain.{
  Address,
  BlockContext,
  Nonce,
  ObftBlock,
  ObftGenesisAccount,
  ObftGenesisData,
  ObftHeader,
  Transaction,
  TransactionType02,
  UInt256
}
import io.iohk.scevm.exec.config.{BlockchainConfigForEvm, EvmConfig}
import io.iohk.scevm.exec.vm.{WorldType, _}
import io.iohk.scevm.ledger.GetByNumberServiceStub
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.TestCoreConfigs
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp

import scala.io.Source

object EVMTestUtils {

  val vm: EVM[WorldType, StorageType] = EVM[WorldType, StorageType]()

  class EVMFixture(
      val genesisBlock: ObftBlock,
      val proxyBuilder: WorldStateBuilder[IO]
  ) {
    def executeTransactionWithWorld(world: WorldType)(
        tx: Transaction,
        sender: Address
    ): IO[ProgramResult[WorldType, StorageType]] =
      vm.run(
        ProgramContext(
          transaction = tx,
          blockContext = world.blockContext,
          senderAddress = sender,
          world = world,
          evmConfig =
            EvmConfig.forBlock(genesisBlock.header.number, BlockchainConfigForEvm(TestCoreConfigs.blockchainConfig))
        )
      )

    def executeTransaction(
        tx: Transaction,
        sender: Address
    ): StateT[IO, WorldType, ProgramResult[WorldType, StorageType]] =
      StateT(world => executeTransactionWithWorld(world)(tx, sender).map(pr => (pr.world, pr)))

    def callContractWithWorld(
        world: WorldType
    )(
        address: Address,
        payload: ByteString,
        sender: Address = Address(0),
        value: UInt256 = 0
    ): IO[ProgramResult[WorldType, StorageType]] =
      executeTransactionWithWorld(world)(buildTransaction(address, payload, value), sender)

    def callContract0(
        address: Address,
        data: ByteString,
        sender: Address = Address(0),
        value: UInt256 = 0
    ): StateT[IO, WorldType, ProgramResult[WorldType, StorageType]] =
      StateT(world =>
        callContractWithWorld(world)(address, data, sender, value)
          .map(pr => (pr.world, pr))
      )

    def callContract(
        address: Address,
        data: ByteString,
        sender: Address = Address(0),
        value: UInt256 = 0
    ): StateT[IO, WorldType, Either[ProgramResultError, ByteString]] =
      callContract0(address, data, sender, value).map(_.toEither)

    /** Get some value from the current state without modifying it */
    def withWorld[T](block: WorldType => IO[T]): StateT[IO, WorldType, T] =
      StateT(world => block(world).map((world, _)))

    def updateWorld(update: WorldType => IO[WorldType]): StateT[IO, WorldType, Unit] =
      StateT(world => update(world).map((_, ())))

    def run[T](instructions: WorldType => IO[T]): IO[T] =
      getWorld(genesisBlock.header)
        .use(instructions)

    def run[T](instructions: StateT[IO, WorldType, T]): IO[T] =
      getWorld(genesisBlock.header)
        .use(instructions.runA)

    private def getWorld(header: ObftHeader): Resource[IO, WorldType] =
      proxyBuilder.getWorldStateForBlock(header.stateRoot, BlockContext.from(header))

    def setCoinbaseAccount(address: Address): StateT[IO, WorldType, Unit] =
      StateT.modify[IO, WorldType](world => world.modifyBlockContext(_.copy(coinbase = address)))
  }

  object EVMFixture {
    def apply(accounts: (Address, ObftGenesisAccount)*): EVMFixture = {
      import cats.effect.unsafe.implicits.global

      val dataSource     = EphemDataSource()
      val storageMetrics = NoOpStorageMetrics[IO]()
      val stateStorage = new ArchiveStateStorage(
        new NodeStorage(dataSource, storageMetrics.readTimeForNode, storageMetrics.writeTimeForNode)
      )
      val codeStorage =
        new EvmCodeStorageImpl(dataSource, storageMetrics.readTimeForEvmCode, storageMetrics.writeTimeForEvmCode)

      val block = ObftGenesisLoader
        .load[IO](codeStorage, stateStorage)(EVMTestUtils.genesisData(accounts.toMap))
        .unsafeRunSync()

      val proxyBuilder = new WorldStateBuilderImpl(
        codeStorage,
        new GetByNumberServiceStub(List(block)),
        stateStorage,
        TestCoreConfigs.blockchainConfig
      )
      new EVMFixture(block, proxyBuilder)
    }
  }

  private def buildTransaction(address: Address, payload: ByteString, value: UInt256) =
    TransactionType02(
      chainId = 0,
      nonce = Nonce.Zero,
      maxPriorityFeePerGas = 0,
      maxFeePerGas = 0,
      gasLimit = Int.MaxValue,
      receivingAddress = address,
      value = value,
      payload = payload,
      accessList = Nil
    )

  def genesisData(accounts: Map[Address, ObftGenesisAccount]): ObftGenesisData =
    ObftGenesisData(
      coinbase = Address("0x0000000000000000000000000000000000000000"),
      gasLimit = 8000000,
      timestamp = 0L.millisToTs,
      alloc = accounts,
      accountStartNonce = Nonce.Zero
    )

  def loadContractCodeFromFile(
      file: String,
      balance: UInt256 = 0,
      storage: Map[UInt256, UInt256] = Map.empty
  ): ObftGenesisAccount = {
    val src = Source.fromResource(file)
    val raw =
      try src.mkString
      finally src.close()
    val code = ByteString(raw.trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
    ObftGenesisAccount(
      balance = balance,
      code = Some(code),
      nonce = None,
      storage = storage
    )
  }
}
