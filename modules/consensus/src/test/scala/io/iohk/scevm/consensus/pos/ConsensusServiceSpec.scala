package io.iohk.scevm.consensus.pos

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.domain.{BlocksBranch, BranchServiceImpl}
import io.iohk.scevm.consensus.metrics.NoOpConsensusMetrics
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionError
import io.iohk.scevm.consensus.validators.ChainingValidator
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.BranchProvider.MissingParent
import io.iohk.scevm.db.storage.{BlockchainStorage, BlocksWriter, EvmCodeStorageImpl, ReceiptStorage}
import io.iohk.scevm.domain.{Address, BlockContext, BlockHash, BlockNumber, Nonce, ObftBlock, ObftHeader, Slot}
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.BlockImportService.ImportedBlock
import io.iohk.scevm.ledger.TransactionExecution.{StateBeforeFailure, TxsExecutionError}
import io.iohk.scevm.metrics.instruments.Histogram
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.mpt.Node.{Encoded, Hash}
import io.iohk.scevm.testing.BlockGenerators._
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalacheck.Gen
import org.scalatest.Outcome
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import BranchExecution.RangeExecutionError
import ConsensusService._
import ObftBlockExecution.BlockExecutionError._

class ConsensusServiceSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  private def setup(chain: List[ObftBlock], stabilityParameter: Int): (BlocksBranch, CurrentBranch, ObftBlock) = {
    val newBlock                       = chain.last
    val initialChain                   = chain.dropRight(1)
    val (stableBlocks, unstableBlocks) = initialChain.splitAt(initialChain.length - stabilityParameter)
    val stable                         = stableBlocks.last
    val belly                          = unstableBlocks.dropRight(1).toVector
    val best                           = unstableBlocks.last
    val initialBranch                  = CurrentBranch(stable.header, best.header)

    (BlocksBranch(stable, belly, best), initialBranch, newBlock)
  }

  "ConsensusService" when {
    "resolve is called" should {
      "return ConnectedBranch with BetterBranch when the chain is extended by an BlockWithValidatedHeader" in {
        fixture =>
          forAll(obftChainBlockWithoutTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
            val (blocksBranch, initialBranch, newBlock) = setup(chain, stabilityParameter)

            val res = (for {
              _ <- chain.traverse(fixture.blocksWriter.insertBlock(_))
              result <- fixture
                          .consensusServiceBuilder(stabilityParameter, alwaysValidBranchExecution)
                          .resolve(ImportedBlock(newBlock, fullyValidated = false), initialBranch)
            } yield result).ioValue

            res shouldBe ConnectedBranch(
              newBlock,
              Some(
                BetterBranch(
                  if (blocksBranch.belly.nonEmpty) Vector(blocksBranch.belly.head) else Vector(blocksBranch.tip),
                  newBlock.header
                )
              )
            )
          }
      }

      "return ConnectedBranch with BetterBranch when the chain is extended by a trusted block" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
          val (blocksBranch, initialBranch, newBlock) = setup(chain, stabilityParameter)

          val res = (for {
            _ <- chain.traverse(fixture.blocksWriter.insertBlock(_))
            result <- fixture
                        .consensusServiceBuilder(stabilityParameter, alwaysValidBranchExecution)
                        .resolve(ImportedBlock(newBlock, fullyValidated = true), initialBranch)
          } yield result).ioValue

          res shouldBe ConnectedBranch(
            newBlock,
            Some(
              BetterBranch(
                if (blocksBranch.belly.nonEmpty) Vector(blocksBranch.belly.head) else Vector(blocksBranch.tip),
                newBlock.header
              )
            )
          )
        }
      }

      "return ConnectedBranch with None when the chain is not extended" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
          val (blocksBranch, initialBranch, _) = setup(chain, stabilityParameter)

          val blockToTest = blocksBranch.ancestor

          val res = (for {
            _ <- chain.traverse(fixture.blocksWriter.insertBlock(_))
            result <- fixture
                        .consensusServiceBuilder(stabilityParameter, alwaysValidBranchExecution)
                        .resolve(ImportedBlock(blockToTest, fullyValidated = false), initialBranch)
          } yield result).ioValue

          res shouldBe ConnectedBranch(blockToTest, None)
        }
      }

      "return DisconnectedBranchMissingParent when the parent of the new block isn't in storage" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
          val (_, initialBranch, newBlock) = setup(chain, stabilityParameter)

          val missingParent = MissingParent(newBlock.header.parentHash, newBlock.number.previous, newBlock.header)

          val res = (for {
            _ <- fixture.blocksWriter.insertBlock(newBlock)
            result <- fixture
                        .consensusServiceBuilder(stabilityParameter, alwaysValidBranchExecution)
                        .resolve(ImportedBlock(newBlock, fullyValidated = false), initialBranch)
          } yield result).ioValue

          res shouldBe DisconnectedBranchMissingParent(newBlock, missingParent)
        }
      }

      "return ForkedBeyondStableBranch when the new block is part of a forked chain" in { fixture =>
        forAll(obftBlockGen, Gen.choose(1, 8)) { (block, stabilityParameter) =>
          // Create two branches having a stable part (length > stabilityParameter) and starting from the same block
          val branch1 = obftBlockChainGen(10, block, obftEmptyBodyBlockGen).sample.get
          val branch2 = obftBlockChainGen(10, block, obftEmptyBodyBlockGen).sample.get

          val (_, initialBranch, _) = setup(branch1, stabilityParameter)

          val newBlockForked = branch2.last

          val res = (for {
            _ <- branch1.traverse(fixture.blocksWriter.insertBlock(_))
            _ <- branch2.traverse(fixture.blocksWriter.insertBlock(_))
            result <- fixture
                        .consensusServiceBuilder(stabilityParameter, alwaysValidBranchExecution)
                        .resolve(ImportedBlock(newBlockForked, fullyValidated = false), initialBranch)
          } yield result).ioValue

          res shouldBe ForkedBeyondStableBranch(newBlockForked)
        }
      }

      "return ConnectedBranch with None when ErrorDuringExecution is returned during the execution" in { fixture =>
        forAll(obftChainBlockWithTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
          val (_, initialBranch, newBlock) = setup(chain, stabilityParameter)

          val res = (for {
            _ <- chain.traverse(fixture.blocksWriter.insertBlock(_))
            result <- fixture
                        .consensusServiceBuilder(
                          stabilityParameter,
                          fixture.alwaysErrorDuringBranchExecution(newBlock)
                        )
                        .resolve(ImportedBlock(newBlock, fullyValidated = false), initialBranch)
          } yield result).ioValue

          res shouldBe ConnectedBranch(newBlock, None)
        }
      }

      "return ConnectedBranch with None when runtime error is returned during the execution" in { fixture =>
        forAll(obftChainBlockWithTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
          val (_, initialBranch, newBlock) = setup(chain, stabilityParameter)

          val res = (for {
            _ <- chain.traverse(fixture.blocksWriter.insertBlock(_))
            result <- fixture
                        .consensusServiceBuilder(
                          stabilityParameter,
                          fixture.alwaysErrorDuringBranchExecution(newBlock)
                        )
                        .resolve(ImportedBlock(newBlock, fullyValidated = false), initialBranch)
          } yield result).ioValue

          res shouldBe ConnectedBranch(newBlock, None)
        }
      }

      "return ConnectedBranch with None when ValidationAfterExecError is returned during the execution" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(10, 30), Gen.choose(1, 8)) { (chain, stabilityParameter) =>
          val (_, initialBranch, newBlock) = setup(chain, stabilityParameter)

          val res = (for {
            _ <- chain.traverse(fixture.blocksWriter.insertBlock(_))
            result <- fixture
                        .consensusServiceBuilder(
                          stabilityParameter,
                          alwaysValidationAfterExecErrorBranchExecution(newBlock)
                        )
                        .resolve(ImportedBlock(newBlock, fullyValidated = false), initialBranch)
          } yield result).ioValue

          res shouldBe ConnectedBranch(newBlock, None)
        }
      }
    }
  }

  case class FixtureParam(
      blocksWriter: BlocksWriter[IO],
      consensusServiceBuilder: (Int, BranchExecution[IO]) => ConsensusService[IO],
      initialState: InMemoryWorldState
  ) {
    def alwaysErrorDuringBranchExecution(block: ObftBlock): BranchExecution[IO] = new BranchExecution[IO] {
      override def execute(branch: BlocksBranch): IO[Either[BlockExecutionError, BlocksBranch]] = {
        val executionError = ErrorDuringExecution(
          block.hash,
          TxsExecutionError(
            block.body.transactionList.head,
            StateBeforeFailure(initialState, 0, Seq.empty),
            "execution failed"
          )
        )
        Either.left[BlockExecutionError, BlocksBranch](executionError).pure[IO]
      }

      override def executeRange(from: ObftHeader, to: ObftHeader): IO[Either[RangeExecutionError, Unit]] =
        Right(()).pure[IO]
    }
  }

  val alwaysValidBranchExecution: BranchExecution[IO] = new BranchExecution[IO] {
    override def execute(branch: BlocksBranch): IO[Either[BlockExecutionError, BlocksBranch]] =
      Either.right[BlockExecutionError, BlocksBranch](branch).pure[IO]
    override def executeRange(from: ObftHeader, to: ObftHeader): IO[Either[RangeExecutionError, Unit]] =
      Right(()).pure[IO]
  }
  def alwaysValidationAfterExecErrorBranchExecution(block: ObftBlock): BranchExecution[IO] = new BranchExecution[IO] {
    override def execute(branch: BlocksBranch): IO[Either[BlockExecutionError, BlocksBranch]] =
      Either
        .left[BlockExecutionError, BlocksBranch](
          ValidationAfterExecError(PostExecutionError(block.hash, "test-error"))
        )
        .pure[IO]

    override def executeRange(from: ObftHeader, to: ObftHeader): IO[Either[RangeExecutionError, Unit]] =
      Right(()).pure[IO]
  }

  def alwaysRuntimeErrorDuringBranchExecution(block: ObftBlock): BranchExecution[IO] = new BranchExecution[IO] {
    override def execute(branch: BlocksBranch): IO[Either[BlockExecutionError, BlocksBranch]] =
      IO.raiseError(new RuntimeException("something went wrong"))

    override def executeRange(from: ObftHeader, to: ObftHeader): IO[Either[RangeExecutionError, Unit]] =
      Right(()).pure[IO]
  }

  // scalastyle:off method.length
  override protected def withFixture(test: OneArgTest): Outcome = {
    val blockchainStorage = BlockchainStorage.unsafeCreate[IO](EphemDataSource())

    val alwaysValidChainingValidator = new ChainingValidator {
      override def validate(
          parent: ObftHeader,
          block: ObftHeader
      ): Either[ChainingValidator.ChainingError, ObftHeader] =
        Right(block)
    }

    val branchService = new BranchServiceImpl[IO](blockchainStorage, blockchainStorage)(alwaysValidChainingValidator)

    val branchResolution = new BranchResolutionImpl[IO](branchService, SimplestHeaderComparator)

    val inMemoryWorldStateProxy = InMemoryWorldState(
      new EvmCodeStorageImpl(
        EphemDataSource(),
        Histogram.noOpClientHistogram(),
        Histogram.noOpClientHistogram()
      ),
      new MptStorage {
        override def get(key: Hash): Option[Encoded]                                         = ???
        override def update(toRemove: Seq[Hash], toUpsert: Seq[(Hash, Encoded)]): MptStorage = ???
      },
      (_, _) => None,
      Nonce(0),
      ByteString.empty,
      noEmptyAccounts = false,
      blockContext = BlockContext(
        BlockHash(ByteString.empty),
        BlockNumber(0),
        Slot(1),
        BigInt(0),
        Address(ByteString.empty),
        UnixTimestamp(1)
      )
    )

    val receiptStorage                                 = ReceiptStorage.unsafeCreate[IO](EphemDataSource())
    val semaphore                                      = Semaphore[IO](1).ioValue
    val branchUpdateListener: BranchUpdateListener[IO] = (_: BetterBranch) => IO.unit

    def consensusServiceBuilder(stabilityParameter: Int, branchExecution: BranchExecution[IO]) =
      new ConsensusServiceImpl[IO](
        branchService,
        branchResolution,
        branchExecution,
        receiptStorage,
        semaphore,
        branchUpdateListener,
        NoOpConsensusMetrics()
      )(stabilityParameter)

    test(
      FixtureParam(
        blockchainStorage,
        consensusServiceBuilder,
        inMemoryWorldStateProxy
      )
    )
  } // scalastyle:on method.length

}
