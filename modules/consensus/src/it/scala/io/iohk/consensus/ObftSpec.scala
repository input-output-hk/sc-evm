package io.iohk.consensus

import cats.effect.IO
import cats.implicits.{showInterpolator, toTraverseOps}
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.ConsensusService.ConsensusResult
import io.iohk.scevm.consensus.pos.{ConsensusService, CurrentBranch}
import io.iohk.scevm.domain.{BlocksDistance, ObftBlock}
import io.iohk.scevm.ledger.BlockImportService
import io.iohk.scevm.ledger.BlockImportService.{ImportedBlock, UnvalidatedBlock}
import io.iohk.scevm.testing.fixtures.GenesisBlock
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.Logger
import org.scalatest.SequentialNestedSuiteExecution
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Random

class ObftSpec
    extends AnyWordSpec
    with DataSourceFixture
    with Matchers
    with Eventually
    with ScalaFutures
    with NormalPatience
    with IOSupport
    with SequentialNestedSuiteExecution
    with Logger {
  lazy val blockchainConfig: BlockchainConfig = ConfigFixture.blockchainConfig

  "Consensus" when {
    val k       = blockchainConfig.stabilityParameter
    val genesis = GenesisBlock.block

    val fixture = InitialisationFixture.init(dataSource, blockchainConfig, ConfigFixture.validators).ioValue
    import fixture._

    def importAndRunConsensus(
        blockImportService: BlockImportService[IO],
        consensusService: ConsensusService[IO],
        block: ObftBlock,
        currentBranch: CurrentBranch.Signal[IO]
    ): IO[ConsensusResult] =
      blockImportService.importAndValidateBlock(UnvalidatedBlock(block)) >>
        currentBranch.get.flatMap(consensusService.resolve(ImportedBlock(block, fullyValidated = false), _))

    consensusInit
      .zip(currentBranch.continuous)
      .evalMap { case (block, branch) =>
        consensusService.resolve(ImportedBlock(block, fullyValidated = false), branch)
      }
      .compile
      .drain
      .unsafeRunAndForget()

    // The first chain
    val initialChain       = generator.genValidExtensionChain(genesis, k * 2)
    val lastOfInitialChain = initialChain.find(_.number == initialChain.last.number - BlocksDistance(k)).get

    // fork between initialChain.stable and initialChain.best
    val betterFork = {
      val numberBetweenStableAndBest = initialChain.last.number - BlocksDistance(Random.nextInt(k))
      val branchingBlock             = initialChain.find(_.number == numberBetweenStableAndBest).get

      generator.genValidExtensionChain(branchingBlock, k * 2)
    }

    val missingParent = generator.genValidExtensionChain(betterFork.last, 1).head
    val suffix1       = generator.genValidExtensionChain(missingParent, k + 2)

    // Debug: print of all blocks generated for these tests
    log.debug(show"- Genesis=$genesis")
    log.debug(show"- Initial chain size=${initialChain.size}")
    initialChain.foreach(block => log.debug(show" -- $block"))

    log.debug(show"- Better fork size=${betterFork.size}")
    betterFork.foreach(block => log.debug(show" -- $block"))

    log.debug(show"- Missing parent=$missingParent")

    log.debug(show"- Suffix 1 size=${suffix1.size}")
    suffix1.foreach(block => log.debug(show" -- $block"))

    "receiving a first branch" should {
      val (beforeK, next) = initialChain.splitAt(k)
      val blockK          = next.head
      val afterK          = next.tail

      "only update best up to k blocks" in {
        beforeK.traverse(block => importAndRunConsensus(importService, consensusService, block, currentBranch)).ioValue

        val CurrentBranch(stable, best) = CurrentBranch.get[IO].ioValue
        assert(best.idTag == beforeK.last.header.idTag)
        assert(stable.idTag == genesis.header.idTag)
      }

      "be persisted (safe to restart)" in {
        val restarted =
          InitialisationFixture.init(dataSource, blockchainConfig, ConfigFixture.validators).ioValue
        restarted.consensusInit
          .evalMap(block =>
            importAndRunConsensus(restarted.importService, restarted.consensusService, block, restarted.currentBranch)
          )
          .compile
          .drain
          .ioValue

        val CurrentBranch(stable, best) = restarted.currentBranch.get.ioValue
        assert(best.hash == beforeK.last.hash)
        assert(stable.hash == genesis.hash)
      }

      "also update stable at block k" in {
        importAndRunConsensus(importService, consensusService, blockK, currentBranch).ioValue

        val CurrentBranch(stable, best) = CurrentBranch.get[IO].ioValue
        assert(best.idTag == blockK.header.idTag)
        val expectedStable = initialChain.find(_.number.value == 1).get
        assert(stable.idTag == expectedStable.header.idTag)
      }

      "continue updating best & stable after k blocks" in {
        afterK.traverse(block => importAndRunConsensus(importService, consensusService, block, currentBranch)).ioValue

        val CurrentBranch(stable, best) = CurrentBranch.get[IO].ioValue
        assert(best.idTag == afterK.last.header.idTag)
        assert(stable.idTag == lastOfInitialChain.header.idTag)
      }
    }

    // At the point initialChain must be entirely persisted in storage
    "receiving a concurrent branch forking before stable" should {
      "import the fork and not change best nor stable" in {
        val branchingBlock = initialChain.find(_.number == lastOfInitialChain.number.previous).get

        val concurrent = generator.genValidExtensionChain(branchingBlock, k)

        concurrent
          .traverse(block => importAndRunConsensus(importService, consensusService, block, currentBranch))
          .ioValue

        concurrent.map { block =>
          fixture.blocksReader
            .getBlock(block.hash)
            .map(persisted => assert(persisted.isDefined))
            .ioValue

          val CurrentBranch(stable, best) = CurrentBranch.get[IO].ioValue
          assert(best.idTag == initialChain.last.header.idTag)
          assert(stable.idTag == lastOfInitialChain.header.idTag)
        }
      }
    }

    "receiving a concurrent branch forking after stable" should {
      val (notBetterCandidate, nextBranch2) = betterFork.span(_.number <= initialChain.last.number)

      "import the fork and not change best nor stable (not a better candidate)" in {
        notBetterCandidate
          .traverse(block => importAndRunConsensus(importService, consensusService, block, currentBranch))
          .ioValue

        notBetterCandidate.map { block =>
          fixture.blocksReader
            .getBlock(block.hash)
            .map(persisted => assert(persisted.isDefined))
            .ioValue

          val CurrentBranch(stable, best) = CurrentBranch.get[IO].ioValue
          assert(best.idTag == initialChain.last.header.idTag)
          assert(stable.idTag == lastOfInitialChain.header.idTag)
        }
      }

      "import the fork and update best (better candidate)" in {
        nextBranch2
          .traverse(block => importAndRunConsensus(importService, consensusService, block, currentBranch))
          .ioValue

        eventually {
          val CurrentBranch(stable, best) = CurrentBranch.get[IO].ioValue
          assert(best.idTag == betterFork.last.header.idTag)
          assert(stable.idTag != lastOfInitialChain.header.idTag)
        }
      }
    }

    // At this point, the betterFork must be entirely stored
    "disconnected" should {
      "update the best reference up to the missing block" in {
        suffix1.traverse(block => importAndRunConsensus(importService, consensusService, block, currentBranch)).ioValue

        fixture.blocksReader
          .getBlockHeader(missingParent.hash)
          .map(missing => assert(missing.isEmpty))
          .ioValue

        val best = CurrentBranch.bestHash[IO].ioValue
        assert(best == missingParent.header.parentHash)
      }

      "update the best reference up to tip of one of the suffixes" in {
        importAndRunConsensus(importService, consensusService, missingParent, currentBranch).ioValue

        fixture.blocksReader
          .getBlockHeader(missingParent.hash)
          .map(missing => assert(missing.isDefined))
          .ioValue

        val best = CurrentBranch.bestHash[IO].ioValue
        assert(best != missingParent.hash)

        (suffix1 :+ missingParent)
          .traverse(h =>
            fixture.blockProvider.getBlock(h.header.number).map {
              case Some(_) => //ok, do nothing
              case None    => fail(s"missing block ${h.header.number}")
            }
          )
          .ioValue
        // for performance reasons this block should also be added to stableNumberMapping
        fixture.stableNumberMappingStorage.getBlockHash(missingParent.header.number).ioValue should not be empty
      }
    }
  }
}
