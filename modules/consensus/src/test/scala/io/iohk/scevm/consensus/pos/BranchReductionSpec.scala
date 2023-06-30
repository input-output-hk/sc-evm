package io.iohk.scevm.consensus.pos

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.domain.BlocksBranch
import io.iohk.scevm.db.storage.ReceiptStorage
import io.iohk.scevm.domain.{BlockHash, LegacyReceipt, Receipt, ReceiptBody, TransactionOutcome}
import io.iohk.scevm.testing.BlockGenerators.obftChainBlockWithoutTransactionsGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BranchReductionSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with MockFactory
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "ConsensusService.reduceBranch" should {
    "reduce a fully executed branch into a branch with only 1 unvalidated block -- the tip" in {
      forAll(fixedSizeBlocksBranchGen(10, numExecutedBlocks = 10)) { case (branch, storage) =>
        assertIOSync {
          /* By design: `BlockBranch` tip is always unvalidated */
          val expected = expectedReducedBranch(branch, 1)
          ConsensusService.reduceBranch(branch, storage).map { reducedBranch =>
            reducedBranch.unexecuted.length shouldEqual 1
            reducedBranch.unexecuted shouldEqual expected.unexecuted
          }
        }
      }
    }

    "return the same branch if no blocks were executed before" in {
      forAll(fixedSizeBlocksBranchGen(10, numExecutedBlocks = 0)) { case (branch, storage) =>
        assertIOSync {
          ConsensusService.reduceBranch(branch, storage).map(_ shouldEqual branch)
        }
      }
    }

    "produce a new branch containing unvalidated blocks only" in {
      val testBranchesGen = for {
        chainSize                <- Gen.chooseNum(2, 50)
        numExecutedBlocks        <- Gen.chooseNum(1, chainSize - 1)
        (branch, storage)        <- fixedSizeBlocksBranchGen(chainSize, numExecutedBlocks)
        expectedUnvalidatedLength = chainSize - numExecutedBlocks
      } yield (branch, storage, expectedUnvalidatedLength)

      forAll(testBranchesGen) { case (branch, storage, expectedUnvalidatedLength) =>
        assertIOSync {
          ConsensusService.reduceBranch(branch, storage).map {
            _.unexecuted shouldEqual expectedReducedBranch(branch, expectedUnvalidatedLength).unexecuted
          }
        }
      }
    }
  }

  def expectedReducedBranch(branch: BlocksBranch, expectedUnvalidatedLength: Int): BlocksBranch =
    if (expectedUnvalidatedLength < branch.belly.length + 1) {
      val unvalidatedBelly = branch.belly.takeRight(expectedUnvalidatedLength)
      BlocksBranch(unvalidatedBelly.head, unvalidatedBelly.tail, branch.tip)
    } else {
      branch
    }

  def fixedSizeBlocksBranchGen(length: Int, numExecutedBlocks: Int): Gen[(BlocksBranch, ReceiptStorage[IO])] =
    blocksBranchGen(length, length, numExecutedBlocks)

  def blocksBranchGen(minSize: Int, maxSize: Int, numExecutedBlocks: Int): Gen[(BlocksBranch, ReceiptStorage[IO])] =
    obftChainBlockWithoutTransactionsGen(minSize, maxSize).map {
      case List(ancestor, tip) =>
        val storage: ReceiptStorage[IO] = new ReceiptStorage[IO] {
          override def getReceiptsByHash(hash: BlockHash): IO[Option[Seq[LegacyReceipt]]] =
            IO.pure(
              Some(List(LegacyReceipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, Seq.empty))))
            )
          override def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): IO[Unit] = IO.unit
        }
        (BlocksBranch(ancestor, tip), storage)
      case chain @ (ancestor +: belly :+ tip) =>
        val branch = BlocksBranch(ancestor, belly.toVector, tip)
        val storage: ReceiptStorage[IO] = new ReceiptStorage[IO] {
          override def getReceiptsByHash(hash: BlockHash): IO[Option[Seq[LegacyReceipt]]] =
            if (chain.take(numExecutedBlocks).exists(_.hash == hash))
              IO.pure(
                Some(
                  List(LegacyReceipt(ReceiptBody(TransactionOutcome.SuccessOutcome, 0, ByteString.empty, Seq.empty)))
                )
              )
            else
              IO.pure(None)
          override def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): IO[Unit] = IO.unit
        }
        (branch, storage)
      case _ =>
        throw new Error("Branch should have at least 2 blocks")
    }
}
