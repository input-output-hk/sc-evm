package io.iohk.scevm.exec.vm

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain.{Account, Address, BlockContext, Nonce, ObftGenesisData, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.metrics.instruments.Histogram
import io.iohk.scevm.testing.{BlockGenerators, TestCoreConfigs}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class InMemoryWorldStateSpec extends AnyFreeSpec with Matchers {

  private val aBlockHeader = BlockGenerators.obftBlockHeaderGen.sample.get

  "create2Address - example 0" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 0
    // address 0x0000000000000000000000000000000000000000
    // salt 0x0000000000000000000000000000000000000000000000000000000000000000
    // init_code 0x00
    // gas (assuming no mem expansion): 32006
    // result: 0x4D1A2e2bB4F88F0250f26Ffff098B0b30B26BF38
    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("0000000000000000000000000000000000000000")),
      UInt256.Zero,
      Hex.decodeUnsafe("00")
    )
    val expected = Hex.decodeUnsafe("4D1A2e2bB4F88F0250f26Ffff098B0b30B26BF38")
    result.bytes shouldBe expected
  }

  "create2Address - example 1" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 1
    // address 0xdeadbeef00000000000000000000000000000000
    // salt 0x0000000000000000000000000000000000000000000000000000000000000000
    // init_code 0x00
    // gas (assuming no mem expansion): 32006
    // result: 0xB928f69Bb1D91Cd65274e3c79d8986362984fDA3
    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("deadbeef00000000000000000000000000000000")),
      UInt256.Zero,
      Hex.decodeUnsafe("00")
    )
    val expected = Hex.decodeUnsafe("B928f69Bb1D91Cd65274e3c79d8986362984fDA3")
    result.bytes shouldBe expected
  }

  "create2Address - example 2" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 2
    // address 0xdeadbeef00000000000000000000000000000000
    // salt 0x000000000000000000000000feed000000000000000000000000000000000000
    // init_code 0x00
    // gas (assuming no mem expansion): 32006
    // result: 0xD04116cDd17beBE565EB2422F2497E06cC1C9833
    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("deadbeef00000000000000000000000000000000")),
      UInt256(Hex.decodeUnsafe("000000000000000000000000feed000000000000000000000000000000000000")),
      Hex.decodeUnsafe("00")
    )
    val expected = Hex.decodeUnsafe("D04116cDd17beBE565EB2422F2497E06cC1C9833")
    result.bytes shouldBe expected
  }

  "create2Address - example 3" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 3
    // address 0x0000000000000000000000000000000000000000
    // salt 0x0000000000000000000000000000000000000000000000000000000000000000
    // init_code 0xdeadbeef
    // gas (assuming no mem expansion): 32006
    // result: 0x70f2b2914A2a4b783FaEFb75f459A580616Fcb5e
    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("0000000000000000000000000000000000000000")),
      UInt256.Zero,
      Hex.decodeUnsafe("deadbeef")
    )
    val expected = Hex.decodeUnsafe("70f2b2914A2a4b783FaEFb75f459A580616Fcb5e")
    result.bytes shouldBe expected
  }

  "create2Address - example 4" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 4
    // address 0x00000000000000000000000000000000deadbeef
    // salt 0x00000000000000000000000000000000000000000000000000000000cafebabe
    // init_code 0xdeadbeef
    // gas (assuming no mem expansion): 32006
    // result: 0x60f3f640a8508fC6a86d45DF051962668E1e8AC7
    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("00000000000000000000000000000000deadbeef")),
      UInt256(Hex.decodeUnsafe("00000000000000000000000000000000000000000000000000000000cafebabe")),
      Hex.decodeUnsafe("deadbeef")
    )
    val expected = Hex.decodeUnsafe("60f3f640a8508fC6a86d45DF051962668E1e8AC7")
    result.bytes shouldBe expected
  }

  "create2Address - example 5" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 5
    // address 0x00000000000000000000000000000000deadbeef
    // salt 0x00000000000000000000000000000000000000000000000000000000cafebabe
    // init_code 0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef
    // gas (assuming no mem expansion): 32012
    // result: 0x1d8bfDC5D46DC4f61D6b6115972536eBE6A8854C

    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("00000000000000000000000000000000deadbeef")),
      UInt256(Hex.decodeUnsafe("00000000000000000000000000000000000000000000000000000000cafebabe")),
      Hex.decodeUnsafe("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
    )
    val expected = Hex.decodeUnsafe("1d8bfDC5D46DC4f61D6b6115972536eBE6A8854C")
    result.bytes shouldBe expected
  }

  "create2Address - example 6" in new TestSetup {
    // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    // Example 6
    // address 0x0000000000000000000000000000000000000000
    // salt 0x0000000000000000000000000000000000000000000000000000000000000000
    // init_code 0x
    // gas (assuming no mem expansion): 32000
    // result: 0xE33C0C7F7df4809055C3ebA6c09CFe4BaF1BD9e0

    val result = worldState.create2Address(
      Address.fromBytesUnsafe(Hex.decodeUnsafe("0000000000000000000000000000000000000000")),
      UInt256.Zero,
      ByteString.empty
    )
    val expected = Hex.decodeUnsafe("E33C0C7F7df4809055C3ebA6c09CFe4BaF1BD9e0")
    result.bytes shouldBe expected
  }

  "allow to create and retrieve an account" in new TestSetup {
    worldState.newEmptyAccount(address1).accountExists(address1) shouldBe true
  }

  "allow to save and retrieve code" in new TestSetup {
    val code = Generators.getByteStringGen(1, 100).sample.get
    worldState.saveCode(address1, code).getCode(address1) shouldEqual code
  }

  "allow to save and get storage" in new TestSetup {
    val addr  = Generators.getUInt256Gen().sample.getOrElse(UInt256.MaxValue).toBigInt
    val value = Generators.getUInt256Gen().sample.getOrElse(UInt256.MaxValue).toBigInt

    val storage = worldState
      .getStorage(address1)
      .store(addr, value)

    worldState.saveStorage(address1, storage).getStorage(address1).load(addr) shouldEqual value
  }

  "allow to transfer value to other address" in new TestSetup {
    val account    = Account(Nonce.Zero, 100)
    val toTransfer = account.balance - 20
    val finalWorldState = worldState
      .saveAccount(address1, account)
      .newEmptyAccount(address2)
      .transfer(address1, address2, UInt256(toTransfer))

    finalWorldState.getGuaranteedAccount(address1).balance shouldEqual (account.balance - toTransfer)
    finalWorldState.getGuaranteedAccount(address2).balance shouldEqual toTransfer
  }

  "not store within contract store if value is zero" in new TestSetup {
    val account                          = Account(Nonce.Zero, 100)
    val worldStateWithAnAccount          = worldState.saveAccount(address1, account)
    val persistedWorldStateWithAnAccount = InMemoryWorldState.persistState(worldStateWithAnAccount)

    val persistedWithContractStorageValue = InMemoryWorldState.persistState(
      persistedWorldStateWithAnAccount.saveStorage(
        address1,
        worldState
          .getStorage(address1)
          .store(UInt256.One, UInt256.Zero)
      )
    )
    persistedWorldStateWithAnAccount.stateRootHash shouldEqual persistedWithContractStorageValue.stateRootHash
  }

  "storing a zero on a contract store position should remove it from the underlying tree" in new TestSetup {
    val account                          = Account(Nonce.Zero, 100)
    val worldStateWithAnAccount          = worldState.saveAccount(address1, account)
    val persistedWorldStateWithAnAccount = InMemoryWorldState.persistState(worldStateWithAnAccount)

    val persistedWithContractStorageValue = InMemoryWorldState.persistState(
      persistedWorldStateWithAnAccount.saveStorage(
        address1,
        worldState
          .getStorage(address1)
          .store(UInt256.One, UInt256.One)
      )
    )
    persistedWorldStateWithAnAccount.stateRootHash.equals(
      persistedWithContractStorageValue.stateRootHash
    ) shouldBe false

    val persistedWithZero = InMemoryWorldState.persistState(
      persistedWorldStateWithAnAccount.saveStorage(
        address1,
        worldState
          .getStorage(address1)
          .store(UInt256.One, UInt256.Zero)
      )
    )

    persistedWorldStateWithAnAccount.stateRootHash shouldEqual persistedWithZero.stateRootHash
  }

  "be able to persist changes and continue working after that" in new TestSetup {
    val account = Account(Nonce.Zero, 100)
    val addr    = UInt256.Zero.toBigInt
    val value   = UInt256.MaxValue.toBigInt
    val code    = ByteString(Hex.decodeAsArrayUnsafe("deadbeefdeadbeefdeadbeef"))

    val validateInitialWorld = (ws: InMemoryWorldState) => {
      ws.accountExists(address1) shouldEqual true
      ws.accountExists(address2) shouldEqual true
      ws.getCode(address1) shouldEqual code
      ws.getStorage(address1).load(addr) shouldEqual value
      ws.getGuaranteedAccount(address1).balance shouldEqual 0
      ws.getGuaranteedAccount(address2).balance shouldEqual account.balance
    }

    // Update WS with some data
    val afterUpdatesWorldState = worldState
      .saveAccount(address1, account)
      .saveCode(address1, code)
      .saveStorage(
        address1,
        worldState
          .getStorage(address1)
          .store(addr, value)
      )
      .newEmptyAccount(address2)
      .transfer(address1, address2, UInt256(account.balance))

    validateInitialWorld(afterUpdatesWorldState)

    // Persist and check
    val persistedWorldState = InMemoryWorldState.persistState(afterUpdatesWorldState)
    validateInitialWorld(persistedWorldState)

    // Create a new WS instance based on storages and new root state and check
    val newWorldState = InMemoryWorldState(
      codeStorage,
      stateStorage.archivingStorage,
      (_, _) => None,
      Nonce.Zero,
      persistedWorldState.stateRootHash,
      noEmptyAccounts = true,
      blockContext = BlockContext.from(aBlockHeader)
    )

    validateInitialWorld(newWorldState)

    // Update this new WS check everything is ok
    val updatedNewWorldState = newWorldState.transfer(address2, address1, UInt256(account.balance))
    updatedNewWorldState.getGuaranteedAccount(address1).balance shouldEqual account.balance
    updatedNewWorldState.getGuaranteedAccount(address2).balance shouldEqual 0
    updatedNewWorldState.getStorage(address1).load(addr) shouldEqual value

    // Persist and check again
    val persistedNewWorldState = InMemoryWorldState.persistState(updatedNewWorldState)

    persistedNewWorldState.getGuaranteedAccount(address1).balance shouldEqual account.balance
    persistedNewWorldState.getGuaranteedAccount(address2).balance shouldEqual 0
    persistedNewWorldState.getStorage(address1).load(addr) shouldEqual value

  }

  "be able to do transfers with the same origin and destination" in new TestSetup {
    val account    = Account(Nonce.Zero, 100)
    val toTransfer = account.balance - 20
    val finalWorldState = worldState
      .saveAccount(address1, account)
      .transfer(address1, address1, UInt256(toTransfer))

    finalWorldState.getGuaranteedAccount(address1).balance shouldEqual account.balance
  }

  "not allow transfer to create empty accounts post EIP161" in new TestSetup {
    val account         = Account(Nonce.Zero, 100)
    val zeroTransfer    = UInt256.Zero
    val nonZeroTransfer = account.balance - 20

    val worldStateAfterEmptyTransfer = postEIP161WorldState
      .saveAccount(address1, account)
      .transfer(address1, address2, zeroTransfer)

    worldStateAfterEmptyTransfer.getGuaranteedAccount(address1).balance shouldEqual account.balance
    worldStateAfterEmptyTransfer.getAccount(address2) shouldBe None

    val finalWorldState = worldStateAfterEmptyTransfer.transfer(address1, address2, nonZeroTransfer)

    finalWorldState.getGuaranteedAccount(address1).balance shouldEqual account.balance - nonZeroTransfer

    val secondAccount = finalWorldState.getGuaranteedAccount(address2)
    secondAccount.balance shouldEqual nonZeroTransfer
    secondAccount.nonce shouldEqual Nonce.Zero
  }

  "correctly mark touched accounts post EIP161" in new TestSetup {
    val account         = Account(Nonce.Zero, 100)
    val zeroTransfer    = UInt256.Zero
    val nonZeroTransfer = account.balance - 80

    val worldAfterSelfTransfer = postEIP161WorldState
      .saveAccount(address1, account)
      .transfer(address1, address1, nonZeroTransfer)

    val worldStateAfterFirstTransfer = worldAfterSelfTransfer
      .transfer(address1, address2, zeroTransfer)

    val worldStateAfterSecondTransfer = worldStateAfterFirstTransfer
      .transfer(address1, address3, nonZeroTransfer)

    worldStateAfterSecondTransfer.touchedAccounts should contain theSameElementsAs Set(address1, address3)
  }

  "update touched accounts using keepPrecompileContract method" in new TestSetup {
    val account         = Account(Nonce.Zero, 100)
    val zeroTransfer    = UInt256.Zero
    val nonZeroTransfer = account.balance - 80

    val precompiledAddress = Address(3)

    val worldAfterSelfTransfer = postEIP161WorldState
      .saveAccount(precompiledAddress, account)
      .transfer(precompiledAddress, precompiledAddress, nonZeroTransfer)

    val worldStateAfterFirstTransfer = worldAfterSelfTransfer
      .saveAccount(address1, account)
      .transfer(address1, address2, zeroTransfer)

    val worldStateAfterSecondTransfer = worldStateAfterFirstTransfer
      .transfer(address1, address3, nonZeroTransfer)

    val postEip161UpdatedWorld = postEIP161WorldState.keepPrecompileTouched(worldStateAfterSecondTransfer)

    postEip161UpdatedWorld.touchedAccounts should contain theSameElementsAs Set(precompiledAddress)
  }

  "correctly determine if account is dead" in new TestSetup {
    val emptyAccountWorld = worldState.newEmptyAccount(address1)

    emptyAccountWorld.accountExists(address1) shouldBe true
    emptyAccountWorld.isAccountDead(address1) shouldBe true

    emptyAccountWorld.accountExists(address2) shouldBe false
    emptyAccountWorld.isAccountDead(address2) shouldBe true
  }

  "remove all ether from existing account" in new TestSetup {
    val startValue = 100

    val account = Account(Nonce(1), startValue)
    ByteString(Hex.decodeAsArrayUnsafe("deadbeefdeadbeefdeadbeef"))

    val initialWorld = InMemoryWorldState.persistState(worldState.saveAccount(address1, account))

    val worldAfterEtherRemoval = initialWorld.removeAllEther(address1)

    val acc1 = worldAfterEtherRemoval.getGuaranteedAccount(address1)

    acc1.nonce shouldEqual Nonce(1)
    acc1.balance shouldEqual 0
  }

  "get changed account from not persisted read only world" in new TestSetup {
    val account = Account(Nonce.Zero, 100)

    val worldStateWithAnAccount = worldState.saveAccount(address1, account)

    val persistedWorldStateWithAnAccount = InMemoryWorldState.persistState(worldStateWithAnAccount)

    val readWorldState = InMemoryWorldState(
      codeStorage,
      stateStorage.readOnlyStorage,
      (_, _) => None,
      Nonce.Zero,
      persistedWorldStateWithAnAccount.stateRootHash,
      noEmptyAccounts = false,
      blockContext = BlockContext.from(aBlockHeader)
    )

    readWorldState.getAccount(address1) shouldEqual Some(account)

    val changedAccount = account.copy(balance = 90)

    val changedReadState = readWorldState
      .saveAccount(address1, changedAccount)

    val changedReadWorld = InMemoryWorldState.persistState(
      changedReadState
    )

    val newReadWorld = InMemoryWorldState(
      codeStorage,
      stateStorage.readOnlyStorage,
      (_, _) => None,
      Nonce.Zero,
      changedReadWorld.stateRootHash,
      noEmptyAccounts = false,
      blockContext = BlockContext.from(aBlockHeader)
    )

    newReadWorld.getAccount(address1) shouldEqual Some(changedAccount)

    changedReadState.getAccount(address1) shouldEqual Some(changedAccount)
  }

  "properly handle address collision during initialisation" in new TestSetup {
    val alreadyExistingAddress = Address("0x6295ee1b4f6dd65047762f924ecd367c17eabf8f")
    val accountBalance         = 100

    val callingAccount = Address("0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b")

    val world1 = InMemoryWorldState.persistState(
      worldState
        .saveAccount(alreadyExistingAddress, Account.empty().increaseBalance(accountBalance))
        .saveAccount(callingAccount, Account.empty().copy(Nonce(1)))
        .saveStorage(alreadyExistingAddress, worldState.getStorage(alreadyExistingAddress).store(0, 1))
    )

    val world2 = InMemoryWorldState(
      codeStorage,
      stateStorage.archivingStorage,
      (_, _) => None,
      Nonce.Zero,
      world1.stateRootHash,
      noEmptyAccounts = false,
      blockContext = BlockContext.from(aBlockHeader)
    )

    world2.getStorage(alreadyExistingAddress).load(0) shouldEqual 1

    val collidingAddress = world2.createAddress(callingAccount)

    collidingAddress shouldEqual alreadyExistingAddress

    val world3 = InMemoryWorldState.persistState(world2.initialiseAccount(collidingAddress))

    world3.getGuaranteedAccount(collidingAddress).balance shouldEqual accountBalance
    world3.getGuaranteedAccount(collidingAddress).nonce shouldEqual Nonce.Zero
    world3.getStorage(collidingAddress).load(0) shouldEqual 0
  }

  trait TestSetup {
    val postEip161Config: EvmConfig = EvmConfig.PostEIP161ConfigBuilder(io.iohk.scevm.exec.vm.fixtures.blockchainConfig)

    val dataSource: EphemDataSource = EphemDataSource()
    val stateStorage = new ArchiveStateStorage(
      new NodeStorage(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
    )
    val codeStorage =
      new EvmCodeStorageImpl(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())

    val worldState: InMemoryWorldState = {
      import cats.effect.unsafe.implicits.global
      val block = ObftGenesisLoader
        .load[IO](codeStorage, stateStorage)(
          ObftGenesisData(
            coinbase = Address("0x0000000000000000000000000000000000000000"),
            gasLimit = 8000000,
            timestamp = 0L.millisToTs,
            alloc = Map.empty,
            TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce
          )
        )
        .unsafeRunSync()

      InMemoryWorldState(
        evmCodeStorage = codeStorage,
        mptStorage = stateStorage.archivingStorage,
        getBlockHashByNumber = (_, _) => Some(block.hash),
        accountStartNonce = TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce,
        stateRootHash = block.header.stateRoot,
        noEmptyAccounts = EvmConfig.forBlock(block.number.next, TestCoreConfigs.blockchainConfig).noEmptyAccounts,
        blockContext = BlockContext.from(aBlockHeader)
      )
    }

    val postEIP161WorldState: InMemoryWorldState = {
      import cats.effect.unsafe.implicits.global
      val block = ObftGenesisLoader
        .load[IO](codeStorage, stateStorage)(
          ObftGenesisData(
            coinbase = Address("0x0000000000000000000000000000000000000000"),
            gasLimit = 8000000,
            timestamp = 0L.millisToTs,
            alloc = Map.empty,
            TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce
          )
        )
        .unsafeRunSync()

      InMemoryWorldState(
        evmCodeStorage = codeStorage,
        mptStorage = stateStorage.archivingStorage,
        getBlockHashByNumber = (_, _) => Some(block.hash),
        accountStartNonce = TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce,
        stateRootHash = block.header.stateRoot,
        noEmptyAccounts = postEip161Config.noEmptyAccounts,
        blockContext = BlockContext.from(aBlockHeader)
      )
    }

    val address1: Address = Address(0x123456)
    val address2: Address = Address(0xabcdef)
    val address3: Address = Address(0xfedcba)
  }
}
