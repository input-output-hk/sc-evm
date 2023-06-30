package io.iohk.scevm.ledger

import cats.Id
import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.consensus.WorldStateBuilderImpl
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.metrics.instruments.Histogram
import io.iohk.scevm.testing.{IOSupport, TestCoreConfigs}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import io.iohk.scevm.wallet.Wallet
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class NonceProviderSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with IOSupport
    with IntegrationPatience {
  private val blockchainConfig = TestCoreConfigs.blockchainConfig

  "should return account start nonce if there are no transactions neither on-chain nor in mempool" in {
    val fixture = createFixture(Map.empty)
    val address = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val nonceProvider =
      new NonceProviderImpl[Id](
        getAllFromMemPool = List.empty,
        blockchainConfig = blockchainConfig
      )

    fixture.run(nonceProvider.getNextNonce(address, _)) shouldBe blockchainConfig.genesisData.accountStartNonce
  }

  "should return on-chain nonce if there is on-chain account associated with that address" in {
    val address      = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val accountNonce = Nonce(BigInt(100))
    val fixture      = createFixture(Map(address -> ObftGenesisAccount(UInt256.Zero, None, Some(accountNonce), Map.empty)))

    val nonceProvider =
      new NonceProviderImpl[Id](
        getAllFromMemPool = List.empty,
        blockchainConfig = blockchainConfig
      )

    fixture.run(nonceProvider.getNextNonce(address, _)) shouldBe accountNonce
  }

  "should return max mempool nonce +1 if there are any transactions in mempool associated with that account" in {
    val address      = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val accountNonce = Nonce(BigInt(100))
    val fixture      = createFixture(Map(address -> ObftGenesisAccount(UInt256.Zero, None, Some(accountNonce), Map.empty)))

    val pendingNonce = Nonce(BigInt(123))
    val signedTx     = createSignedTx(address, pendingNonce)
    val nonceProvider =
      new NonceProviderImpl[Id](
        getAllFromMemPool = List(signedTx),
        blockchainConfig = blockchainConfig
      )

    fixture.run(nonceProvider.getNextNonce(address, _)) shouldBe pendingNonce.increaseOne
  }

  private def createSignedTx(address: Address, nonce: Nonce) = {
    val prvKey: ECDSA.PrivateKey =
      ECDSA.PrivateKey.fromHexUnsafe("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f")
    val wallet: Wallet = Wallet(address, prvKey)
    val chainId        = blockchainConfig.chainId

    val txValue = 128000

    val tx = LegacyTransaction(
      nonce = nonce,
      gasPrice = 0,
      gasLimit = 90000,
      receivingAddress = Address(42),
      value = txValue,
      payload = ByteString.empty
    )
    wallet.signTx(tx, Some(chainId))
  }

  case class Fixture(genesisBlock: ObftBlock, worldBuilder: WorldStateBuilderImpl[IO]) {
    def run[T](op: WorldType => Id[T]): Id[T] =
      worldBuilder
        .getWorldStateForBlock(genesisBlock.header.stateRoot, BlockContext.from(genesisBlock.header))
        .use(w => IO.delay(op(w)))
        .ioValue
  }

  private def createFixture(alloc: Map[Address, ObftGenesisAccount]) = {
    val dataSource = EphemDataSource()
    val stateStorage = new ArchiveStateStorage(
      new NodeStorage(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
    )
    val codeStorage =
      new EvmCodeStorageImpl(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())

    val block = ObftGenesisLoader
      .load[IO](codeStorage, stateStorage)(
        ObftGenesisData(
          coinbase = Address("0x0000000000000000000000000000000000000000"),
          gasLimit = 8000000,
          timestamp = 0L.millisToTs,
          alloc = alloc,
          accountStartNonce = TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce
        )
      )
      .unsafeRunSync()

    val worldBuilder = new WorldStateBuilderImpl(
      codeStorage,
      new GetByNumberServiceStub(List(block)),
      stateStorage,
      TestCoreConfigs.blockchainConfig
    )

    Fixture(block, worldBuilder)
  }
}
