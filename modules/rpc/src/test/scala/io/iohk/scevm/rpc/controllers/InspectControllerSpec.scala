package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import cats.implicits.toTraverseOps
import fs2.concurrent.SignallingRef
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.dataSource.{DataSource, EphemDataSource}
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{Address, BlockHash, BlockOffset, ObftBlock, ObftBody, ObftHeader, Slot}
import io.iohk.scevm.ledger.{BlockProvider, BlockProviderImpl}
import io.iohk.scevm.testing.fixtures.GenesisBlock
import io.iohk.scevm.testing.fixtures.ValidBlock.dummySig
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalatest.EitherValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAsyncWordSpec

class InspectControllerSpec
    extends FixtureAsyncWordSpec
    with Matchers
    with AsyncDataSourceFixture
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "InspectControllerSpec" when {
    "getChain is called" should {
      "return unstable blocks on empty request" in { fixture =>
        val inspectController = new InspectController(
          fixture.currentBranchSignal,
          fixture.blockProvider,
          fixture.branchProvider
        )

        val chainResponse  = inspectController.getChain(None).ioValue
        val expectedHashes = fixture.dag.takeRight(7).map(_.hash).toSet
        chainResponse.value.nodes.map(_.hash).toSet shouldBe expectedHashes
        chainResponse.value.stable shouldBe fixture.stable
      }

      "return best block on offset 0" in { fixture =>
        val inspectController = new InspectController(
          fixture.currentBranchSignal,
          fixture.blockProvider,
          fixture.branchProvider
        )

        val chainResponse  = inspectController.getChain(Some(BlockOffset(0))).ioValue
        val expectedHashes = Set(fixture.dag(6).hash)
        chainResponse.value.nodes.map(_.hash).toSet shouldBe expectedHashes
        chainResponse.value.stable shouldBe fixture.stable
      }

      "returns whole dag for large offset" in { fixture =>
        val inspectController = new InspectController(
          fixture.currentBranchSignal,
          fixture.blockProvider,
          fixture.branchProvider
        )

        val chainResponse  = inspectController.getChain(Some(BlockOffset(100))).ioValue
        val expectedHashes = fixture.dag.map(_.hash).toSet
        chainResponse.value.nodes.map(_.hash).toSet shouldBe expectedHashes
        chainResponse.value.stable shouldBe fixture.stable
      }
    }
  }

  case class FixtureParam(
      currentBranchSignal: CurrentBranch.Signal[IO],
      blockProvider: BlockProvider[IO],
      branchProvider: BranchProvider[IO],
      dag: List[ObftBlock],
      stable: BlockHash
  )

  override def initFixture(dataSource: DataSource): FixtureParam = {
    val genesisBlock = GenesisBlock.block
    implicit val currentBranch: SignallingRef[IO, CurrentBranch] =
      SignallingRef[IO].of(CurrentBranch(genesisBlock.header)).unsafeRunSync()

    val blockchainStorage          = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
    val stableNumberMappingStorage = new StableNumberMappingStorageImpl[IO](EphemDataSource())
    val getByNumberService: GetByNumberService[IO] = new GetByNumberServiceImpl(
      blockchainStorage,
      stableNumberMappingStorage
    )

    //A
    val b1a = b(genesisBlock, 1)
    val b2a = b(b1a, 2)
    val b3a = b(b2a, 3)
    val b4a = b(b3a, 4)
    val b5a = b(b4a, 5)
    val b6a = b(b5a, 6)

    //B, fork
    val b4b = b(b3a, 4)
    val b5b = b(b4b, 5)
    val b6b = b(b5b, 6)

    val dag = List(genesisBlock, b1a, b2a, b3a, b4a, b5a, b6a, b4b, b5b, b6b)

    (for {
      _ <- insertDag(blockchainStorage, dag)
      _ <- currentBranch.set(CurrentBranch(b3a.header, b6a.header))
    } yield ()).unsafeRunSync()

    val blockProvider = new BlockProviderImpl[IO](blockchainStorage, getByNumberService)

    FixtureParam(currentBranch, blockProvider, blockchainStorage, dag, b3a.hash)
  }

  private def insertDag(blockchainStorage: BlocksWriter[IO], blocks: List[ObftBlock]): IO[Unit] =
    blocks.traverse(blockchainStorage.insertBlock).void

  private def b(parent: ObftBlock, slotNo: Int): ObftBlock = {
    val header: ObftHeader = ObftHeader(
      parentHash = parent.hash,
      beneficiary = Address("df7d7e053933b5cc24372f878c90e62dadad5d42"),
      stateRoot = Hex.decodeUnsafe("087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67"),
      transactionsRoot = Hex.decodeUnsafe("8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac"),
      receiptsRoot = Hex.decodeUnsafe("8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d"),
      logsBloom = Hex.decodeUnsafe("0" * 512),
      number = parent.number.next,
      slotNumber = Slot(slotNo),
      gasLimit = 4699996,
      gasUsed = 84000,
      unixTimestamp = System.currentTimeMillis().millisToTs,
      publicSigningKey = ECDSA.PublicKey.Zero,
      signature = dummySig
    )
    val body: ObftBody = ObftBody(Seq())
    ObftBlock(header, body)
  }
}
