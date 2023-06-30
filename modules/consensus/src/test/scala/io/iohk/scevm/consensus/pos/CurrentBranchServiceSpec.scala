package io.iohk.scevm.consensus.pos

import cats.effect.IO
import cats.syntax.all._
import io.iohk.scevm.consensus.metrics.{NoOpBlockMetrics, NoOpConsensusMetrics, TimeToFinalizationTracker}
import io.iohk.scevm.consensus.testing.BetterBranchGenerators.singleElementBetterBranchGen
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.fixtures.GenesisBlock
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience, fixtures}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatest.{Assertions, Outcome}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import CurrentBranchService.InconsistentStableNumberMappingStorage

class CurrentBranchServiceSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "CurrentBranchService" when {

    "calling init" should {
      "ensure that initial header is stored" in { _ =>
        val initialHeader = BlockGenerators.obftBlockHeaderGen.sample.get

        val storageMetrics = NoOpStorageMetrics[IO]()

        forAll(Gen.option(BlockGenerators.obftBlockGen)) { stableOpt =>
          val test = for {
            // init empty storages
            appStorage   <- IO.delay(ObftAppStorage.unsafeCreate[IO](EphemDataSource()))
            blocksReader <- IO.delay(BlockchainStorage.unsafeCreate[IO](EphemDataSource()))
            stableNumberMappingStorage <-
              IO.delay(new StableNumberMappingStorageImpl[IO](EphemDataSource()))
            stableTransactionMappingStorage <-
              IO.delay(new StableTransactionMappingStorageImpl[IO](EphemDataSource()))
            // given
            _ <- stableOpt.traverse(blocksReader.insertBlock)
            // when
            _ <- CurrentBranchService.init(
                   appStorage,
                   blocksReader,
                   stableNumberMappingStorage,
                   stableTransactionMappingStorage,
                   initialHeader,
                   NoOpBlockMetrics[IO](),
                   storageMetrics,
                   TimeToFinalizationTracker.noop[IO],
                   NoOpConsensusMetrics[IO]()
                 )
          } yield assert(appStorage.getLatestStableBlockHash.ioValue.isDefined)

          test.ioValue
        }
      }
    }

    "calling newBranch" should {
      "succeed inserting a connected branch" in { fixture =>
        val test = for {
          // given
          CurrentBranch(stableBlock, _) <- fixture.currentBranchService.signal.get
          betterBranch                   = singleElementBetterBranchGen(stableBlock).sample.get
          _                             <- insertBranch(fixture.blocksWriter, fixture.stableNumberMappingStorage)(betterBranch)
          // when
          _ <- fixture.currentBranchService.newBranch(betterBranch)
          // then
          assertions <- betterBranch.stablePart.traverse { block =>
                          fixture.blocksReader.getBlock(block.hash).map(_ shouldBe Some(block)) >>
                            fixture.blocksReader.getBlockBody(block.hash).map(_ shouldBe Some(block.body)) >>
                            fixture.blocksReader
                              .getBlockHeader(block.hash)
                              .map(_ shouldBe Some(block.header)) >>
                            fixture.stableNumberMappingStorage
                              .getBlockHash(block.number)
                              .map(_ shouldBe Some(block.hash))
                        }
        } yield assertions

        test.ioValue
      }

      "fail inserting a connected branch with invalid StableNumberMapping" in { fixture =>
        val test = for {
          // given
          // remove entry from StableNumberMapping for the current stable block to trigger an error
          _                             <- IO.delay(fixture.stableNumberMappingStorage.remove(fixture.stableBlock.number).commit())
          CurrentBranch(stableBlock, _) <- fixture.currentBranchService.signal.get
          betterBranch                   = singleElementBetterBranchGen(stableBlock).sample.get
          _                             <- insertBranch(fixture.blocksWriter, fixture.stableNumberMappingStorage)(betterBranch)
          // when
          _ <- fixture.currentBranchService.newBranch(betterBranch)
        } yield ()

        test
          .map(_ => Assertions.fail("expected error to be thrown"))
          .handleError {
            case InconsistentStableNumberMappingStorage(_, number) if number == BigInt(0) => Assertions.succeed
            case _                                                                        => Assertions.fail(s"expected ${InconsistentStableNumberMappingStorage.getClass.getName}")
          }
          .ioValue
      }
    }
  }

  case class FixtureParam(
      blocksReader: BlocksReader[IO],
      blocksWriter: BlocksWriter[IO],
      stableNumberMappingStorage: StableNumberMappingStorage[IO],
      currentBranchService: CurrentBranchService[IO],
      stableBlock: ObftBlock
  )

  override protected def withFixture(test: OneArgTest): Outcome = {

    val dataSource     = EphemDataSource()
    val storageMetrics = NoOpStorageMetrics[IO]()
    val fixtureProgram = for {
      appStorage                <- IO.delay(ObftAppStorage.unsafeCreate[IO](dataSource))
      blockchainStorage          = BlockchainStorage.unsafeCreate[IO](dataSource)
      stableNumberMappingStorage = new StableNumberMappingStorageImpl[IO](dataSource)
      transactionMappingStorage  = new StableTransactionMappingStorageImpl[IO](dataSource)
      noOpBlockMetrics           = NoOpBlockMetrics[IO]()
      noOpConsensusMetrics       = NoOpConsensusMetrics[IO]()
      currentBranchService <- CurrentBranchService.init[IO](
                                appStorage,
                                blockchainStorage,
                                stableNumberMappingStorage,
                                transactionMappingStorage,
                                GenesisBlock.header,
                                noOpBlockMetrics,
                                storageMetrics,
                                TimeToFinalizationTracker.noop[IO],
                                noOpConsensusMetrics
                              )
      genesisBlock = fixtures.GenesisBlock.block
      _           <- insertBlock(blockchainStorage, stableNumberMappingStorage)(genesisBlock)
    } yield FixtureParam(
      blockchainStorage,
      blockchainStorage,
      stableNumberMappingStorage,
      currentBranchService,
      genesisBlock
    )

    val fixtureParam = fixtureProgram.ioValue

    test(fixtureParam)
  }

  private def insertBlock(
      blocksWriter: BlocksWriter[IO],
      stableNumberMappingStorage: StableNumberMappingStorage[IO]
  )(block: ObftBlock): IO[Unit] =
    blocksWriter.insertBlock(block) >>
      IO.delay(stableNumberMappingStorage.insertBlockHashWithoutCommit(block.number, block.hash).commit())

  private def insertBranch(
      blocksWriter: BlocksWriter[IO],
      stableNumberMappingStorage: StableNumberMappingStorage[IO]
  )(branch: ConsensusService.BetterBranch): IO[Unit] =
    branch.stablePart.traverse_(insertBlock(blocksWriter, stableNumberMappingStorage))
}
