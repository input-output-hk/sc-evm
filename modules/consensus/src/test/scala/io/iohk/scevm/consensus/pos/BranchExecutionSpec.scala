package io.iohk.scevm.consensus.pos

import cats.effect.{IO, Resource}
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.ObftBlockExecution.{BlockExecutionError, BlockExecutionResult}
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.BranchProvider.BranchRetrievalError
import io.iohk.scevm.db.storage.{BlocksReader, BranchProvider, ReceiptStorage}
import io.iohk.scevm.domain.{BlockContext, BlockHash, ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.testing.BlockGenerators.obftChainHeaderGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import BranchExecution.RangeExecutionError.BlockError

class BranchExecutionSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures
    with NormalPatience
    with MockFactory
    with IOSupport {

  type TestMonad[T] = IO[T]

  "BranchExecution" when {
    val mockedworldStateBuilder =
      new WorldStateBuilder[IO] {
        val world = TestInMemoryWorldState.worldStateGen.sample.get

        override def getWorldStateForBlock(stateRoot: ByteString, blockContext: BlockContext) =
          Resource.pure(world)
      }

    val validBlockExecution: ObftBlockExecution[TestMonad] = new ObftBlockExecution[TestMonad] {
      override def executeBlock(
          block: ObftBlock,
          state: InMemoryWorldState
      ): TestMonad[Either[BlockExecutionError, BlockExecutionResult]] =
        Either.right[BlockExecutionError, BlockExecutionResult](BlockExecutionResult(state)).pure[TestMonad]
    }

    "executeRange" should {
      "pass when validation and execution pass" in {
        // given
        val chain = obftChainHeaderGen(3, 10).sample.get

        val ancestor = chain.head
        val blocks   = chain.tail

        val receiptStorage = ReceiptStorage.unsafeCreate[IO](EphemDataSource())
        val tested: BranchExecutionImpl[TestMonad] = {

          val blocksReader = new BlocksReader[TestMonad] {
            override def getBlockHeader(hash: BlockHash): TestMonad[Option[ObftHeader]] = ???
            override def getBlockBody(hash: BlockHash): TestMonad[Option[ObftBody]]     = ???

            override def getBlock(hash: BlockHash): TestMonad[Option[ObftBlock]] =
              chain.map(header => (header.hash, ObftBlock(header, ObftBody.empty))).toMap.get(hash).pure[TestMonad]
          }

          val branchProvider = new BranchProvider[TestMonad] {
            override def findSuffixesTips(parent: BlockHash): TestMonad[Seq[ObftHeader]] = ???
            override def getChildren(parent: BlockHash): TestMonad[Seq[ObftHeader]]      = ???
            override def fetchChain(
                from: ObftHeader,
                to: ObftHeader
            ): TestMonad[Either[BranchRetrievalError, List[ObftHeader]]] =
              chain
                .asRight[BranchRetrievalError]
                .pure[TestMonad]
          }

          new BranchExecutionImpl[TestMonad](
            validBlockExecution,
            blocksReader,
            branchProvider,
            receiptStorage,
            mockedworldStateBuilder
          )
        }

        // when
        val test = for {
          res1 <- receiptStorage.getReceiptsByHash(chain.last.hash)
          _     = res1 shouldBe None
          _    <- tested.executeRange(ancestor, blocks.last)
          res2 <- receiptStorage.getReceiptsByHash(chain.last.hash)
          _     = res2 shouldBe Some(List())
        } yield ()

        test.ioValue
      }

      "terminate early when validation fails" in {
        // given
        val failingBlockIndex = 5
        val minSize           = failingBlockIndex * 2
        val chain             = obftChainHeaderGen(minSize, minSize * 2).sample.get

        val ancestor     = chain.head
        val failingBlock = chain(failingBlockIndex)
        val blocks       = chain.tail

        val receiptStorage = ReceiptStorage.unsafeCreate[IO](EphemDataSource())
        val tested: BranchExecutionImpl[TestMonad] = {

          val blocksReader = new BlocksReader[TestMonad] {
            override def getBlockHeader(hash: BlockHash): TestMonad[Option[ObftHeader]] = ???
            override def getBlockBody(hash: BlockHash): TestMonad[Option[ObftBody]]     = ???

            // returns only up to the failing block
            override def getBlock(hash: BlockHash): TestMonad[Option[ObftBlock]] =
              chain
                .take(failingBlockIndex + 1)
                .map(header => (header.hash, ObftBlock(header, ObftBody.empty)))
                .toMap
                .get(hash)
                .pure[TestMonad]
          }

          val branchProvider = new BranchProvider[TestMonad] {
            override def findSuffixesTips(parent: BlockHash): TestMonad[Seq[ObftHeader]] = ???
            override def getChildren(parent: BlockHash): TestMonad[Seq[ObftHeader]]      = ???
            override def fetchChain(
                from: ObftHeader,
                to: ObftHeader
            ): TestMonad[Either[BranchRetrievalError, List[ObftHeader]]] =
              chain
                .asRight[BranchRetrievalError]
                .pure[TestMonad]
          }

          val invalidBlockExecution: ObftBlockExecution[TestMonad] = new ObftBlockExecution[TestMonad] {
            override def executeBlock(
                block: ObftBlock,
                state: InMemoryWorldState
            ): TestMonad[Either[BlockExecutionError, BlockExecutionResult]] =
              Either
                .cond(
                  block.header != failingBlock,
                  BlockExecutionResult(state),
                  BlockExecutionError.ValidationAfterExecError(PostExecutionError(block.hash, "test-error"))
                )
                .pure[TestMonad]
          }

          new BranchExecutionImpl[TestMonad](
            invalidBlockExecution,
            blocksReader,
            branchProvider,
            receiptStorage,
            mockedworldStateBuilder
          )
        }

        // when
        val next = chain(failingBlockIndex + 1)

        val test = for {
          _   <- receiptStorage.getReceiptsByHash(failingBlock.parentHash).map(_ shouldBe None)
          _   <- receiptStorage.getReceiptsByHash(next.hash).map(_ shouldBe None)
          res <- tested.executeRange(ancestor, blocks.last)
          _ = res shouldBe Left(
                BlockError(
                  BlockExecutionError.ValidationAfterExecError(PostExecutionError(failingBlock.hash, "test-error"))
                )
              )
          _ <- receiptStorage.getReceiptsByHash(failingBlock.parentHash).map(_ shouldBe Some(List()))
          _ <- receiptStorage.getReceiptsByHash(next.hash).map(_ shouldBe None)
        } yield ()

        test.ioValue
      }
    }
  }

}
