package io.iohk.scevm.sync

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits.toTraverseOps
import com.softwaremill.quicklens._
import io.iohk.bytes.ByteString
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{Account, Address, BlockContext, Nonce, ObftBlock, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.exec.vm.InMemoryWorldState.persistState
import io.iohk.scevm.mpt._
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.{GetNodeData, NodeData}
import io.iohk.scevm.network.{Generators => NetworkGenerators, MptNodeFetcherImpl, PeerChannel, PeerId, PeerWithInfo}
import io.iohk.scevm.testing.{
  BlockGenerators,
  Generators => TestingGenerators,
  IOSupport,
  NormalPatience,
  TestCoreConfigs
}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.reflect.ClassTag

final case class EthAccount(
    account: Account,
    address: Address,
    code: Array[Byte],
    storage: Map[BigInt, BigInt]
)
class MptNodeFetcherSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  "MptNodeFetcher" should {
    "download all mpt nodes starting from a given state root hash" in {
      forAll {
        for {
          genesis <-
            BlockGenerators.obftBlockGen.map(_.modify(_.header.stateRoot).setTo(TestingGenerators.emptyStorageRoot))
          ethAccounts      <- Gen.listOf(ethAccountGen)
          peerWithWithInfo <- NetworkGenerators.peerWithInfoGen
        } yield (genesis, ethAccounts, peerWithWithInfo)
      } { case (genesis, ethAccounts, peerWithInfo: PeerWithInfo) =>
        // ----------------- Initial world setup -----------------
        val initialEphemeralDataSource = EphemDataSource()
        val initialStateStorage        = new ArchiveStateStorage(new NodeStorage(initialEphemeralDataSource))
        val initialEvmCodeStorage      = new EvmCodeStorageImpl(initialEphemeralDataSource)
        val initialWorld =
          createWorld(
            genesis,
            initialEvmCodeStorage,
            initialStateStorage.archivingStorage,
            genesis.header.stateRoot
          )
        val initialState = persistState(ethAccounts.foldLeft(initialWorld) { case (world, ethAccount) =>
          val worldWithAccount = world
            .saveAccount(ethAccount.address, ethAccount.account)
            .saveCode(ethAccount.address, ByteString(ethAccount.code))
          val accountStorage = ethAccount.storage.foldLeft(worldWithAccount.getStorage(ethAccount.address)) {
            case (storage, (key, value)) =>
              storage.store(key, value)
          }
          worldWithAccount.saveStorage(ethAccount.address, accountStorage)
        })
        val initialStateRoot = initialState.stateRootHash

        // ----------------- Resulting world setup -----------------
        val resultingEphemeralDataSource = EphemDataSource()
        val resultingNodeStorage         = new NodeStorage(resultingEphemeralDataSource)
        val resultingStateStorage        = StateStorage(resultingNodeStorage)
        val resultingEvmCodeStorage      = new EvmCodeStorageImpl(resultingEphemeralDataSource)

        // ---------------------- Mocks --------------------------
        val mockedChannel: PeerChannel[IO] = createMockChannel(initialState)
        val peerIds: NonEmptyList[PeerId]  = NonEmptyList.of(peerWithInfo.id)

        // ----------------------- Test -------------------------
        val mptNodeFetcher =
          new MptNodeFetcherImpl[IO](mockedChannel, resultingStateStorage, resultingEvmCodeStorage, 1000)
        mptNodeFetcher.fetch(List(initialStateRoot), peerIds).ioValue

        // ----------------- Verification -----------------
        val resultingWorld = createWorld(
          genesis,
          resultingEvmCodeStorage,
          resultingNodeStorage,
          initialStateRoot
        )

        ethAccounts.foreach { ethAccount =>
          // verify accounts
          resultingWorld.getAccount(ethAccount.address).map { account =>
            assert(account.nonce == ethAccount.account.nonce)
            assert(account.balance == ethAccount.account.balance)
          }

          // verify storage
          ethAccount.storage.foreach { case (key, value) =>
            assert(resultingWorld.getStorage(ethAccount.address).load(key) == value)
          }

          // verify code
          assert(ByteString(ethAccount.code) == resultingWorld.getCode(ethAccount.address))
        }
      }
    }

    "limit the amount of requested nodes according to `maxMptNodesPerRequest`" in {
      forAll {
        for {
          genesis <-
            BlockGenerators.obftBlockGen.map(_.modify(_.header.stateRoot).setTo(TestingGenerators.emptyStorageRoot))
          ethAccounts           <- Gen.listOf(ethAccountGen)
          peerWithWithInfo      <- NetworkGenerators.peerWithInfoGen
          maxMptNodesPerRequest <- Gen.choose(500, 1000)
        } yield (genesis, ethAccounts, peerWithWithInfo, maxMptNodesPerRequest)
      } { case (genesis, ethAccounts, peerWithInfo, maxMptNodesPerRequest) =>
        // ----------------- Initial world setup -----------------
        val initialEphemeralDataSource = EphemDataSource()
        val initialStateStorage        = new ArchiveStateStorage(new NodeStorage(initialEphemeralDataSource))
        val initialEvmCodeStorage      = new EvmCodeStorageImpl(initialEphemeralDataSource)
        val initialWorld =
          createWorld(
            genesis,
            initialEvmCodeStorage,
            initialStateStorage.archivingStorage,
            genesis.header.stateRoot
          )
        val initialState = persistState(ethAccounts.foldLeft(initialWorld) { case (world, ethAccount) =>
          val worldWithAccount = world
            .saveAccount(ethAccount.address, ethAccount.account)
            .saveCode(ethAccount.address, ByteString(ethAccount.code))
          val accountStorage = ethAccount.storage.foldLeft(worldWithAccount.getStorage(ethAccount.address)) {
            case (storage, (key, value)) =>
              storage.store(key, value)
          }
          worldWithAccount.saveStorage(ethAccount.address, accountStorage)
        })
        val initialStateRoot = initialState.stateRootHash

        // ----------------- Resulting world setup -----------------
        val resultingEphemeralDataSource = EphemDataSource()
        val resultingNodeStorage         = new NodeStorage(resultingEphemeralDataSource)
        val resultingStateStorage        = StateStorage(resultingNodeStorage)
        val resultingEvmCodeStorage      = new EvmCodeStorageImpl(resultingEphemeralDataSource)

        // ---------------------- Mocks --------------------------
        val mockedChannel: PeerChannel[IO] = createMockChannel(initialState)
        val observedChannel: PeerChannel[IO] = new PeerChannel[IO] {
          override def askPeers[Response <: OBFT1.ResponseMessage: ClassTag](
              peerIds: NonEmptyList[PeerId],
              requestMessage: OBFT1.RequestMessage
          ): IO[Either[PeerChannel.MultiplePeersAskFailure, Response]] =
            requestMessage match {
              case GetNodeData(_, mptElementsHashes) =>
                if (mptElementsHashes.length > maxMptNodesPerRequest) {
                  IO.raiseError(
                    new RuntimeException(
                      s"MPTFetcher requested ${mptElementsHashes.length} nodes which is more than the limit ($maxMptNodesPerRequest)"
                    )
                  )
                } else
                  mockedChannel.askPeers(peerIds, requestMessage)
              case _ =>
                IO.raiseError(new RuntimeException("Unexpected request"))
            }
        }
        val peerIds: NonEmptyList[PeerId] = NonEmptyList.of(peerWithInfo.id)

        // ----------------------- Test -------------------------
        val mptNodeFetcher =
          new MptNodeFetcherImpl[IO](
            observedChannel,
            resultingStateStorage,
            resultingEvmCodeStorage,
            maxMptNodesPerRequest
          )
        mptNodeFetcher.fetch(List(initialStateRoot), peerIds).ioValue

        // ----------------- Verification -----------------
        val resultingWorld = createWorld(
          genesis,
          resultingEvmCodeStorage,
          resultingNodeStorage,
          initialStateRoot
        )

        ethAccounts.foreach { ethAccount =>
          // verify accounts
          resultingWorld.getAccount(ethAccount.address).map { account =>
            assert(account.nonce == ethAccount.account.nonce)
            assert(account.balance == ethAccount.account.balance)
          }

          // verify storage
          ethAccount.storage.foreach { case (key, value) =>
            assert(resultingWorld.getStorage(ethAccount.address).load(key) == value)
          }

          // verify code
          assert(ByteString(ethAccount.code) == resultingWorld.getCode(ethAccount.address))
        }
      }
    }
  }

  def evmCodeGen(n: Int): Gen[Array[Byte]] =
    TestingGenerators.byteArrayOfNItemsGen(n).map(a => 0.toByte +: a.tail)

  def addressesGen(num: Int): Gen[List[Address]] = Gen.listOfN(num, TestingGenerators.addressGen)

  def positiveBigIntGen: Gen[BigInt] = Gen.chooseNum(0, Long.MaxValue).map(BigInt(_))

  def regularAccountGen: Gen[Account] = for {
    nonce   <- positiveBigIntGen.map(Nonce(_))
    balance <- positiveBigIntGen.map(UInt256(_))
  } yield Account(nonce, balance)

  def ethAccountGen: Gen[EthAccount] = for {
    account <- regularAccountGen
    address <- TestingGenerators.addressGen
    code    <- evmCodeGen(100)
    storage <- Gen.mapOfN(100, Gen.zip(positiveBigIntGen, positiveBigIntGen))
  } yield EthAccount(account, address, code, storage)

  private def createWorld(
      genesis: ObftBlock,
      evmCodeStorage: EvmCodeStorage,
      mptStorage: MptStorage,
      stateRootHash: ByteString
  ): InMemoryWorldState =
    InMemoryWorldState(
      evmCodeStorage = evmCodeStorage,
      mptStorage = mptStorage,
      getBlockHashByNumber = (_, _) => Some(genesis.hash),
      accountStartNonce = TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce,
      stateRootHash = stateRootHash,
      noEmptyAccounts = EvmConfig.forBlock(genesis.number.next, TestCoreConfigs.blockchainConfig).noEmptyAccounts,
      blockContext = BlockContext.from(genesis.header)
    )

  private def createMockChannel(world: InMemoryWorldState): PeerChannel[IO] =
    new PeerChannel[IO] {
      override def askPeers[Response <: OBFT1.ResponseMessage: ClassTag](
          peerIds: NonEmptyList[PeerId],
          requestMessage: OBFT1.RequestMessage
      ): IO[Either[PeerChannel.MultiplePeersAskFailure, Response]] = requestMessage match {
        case GetNodeData(requestId, mptElementsHashes) =>
          mptElementsHashes
            .traverse(hash =>
              IO.delay {
                world.stateStorage
                  .get(hash)
                  .map(ByteString(_))
                  .orElse(world.evmCodeStorage.get(hash))
              }
            )
            .map { blobs =>
              Right[PeerChannel.MultiplePeersAskFailure, Response](
                NodeData(requestId, blobs.flatten).asInstanceOf[Response]
              )
            }
        case _ => fail("Unexpected request message")
      }
    }
}
