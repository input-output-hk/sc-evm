package io.iohk.scevm.consensus.domain

import cats.effect.IO
import cats.syntax.all._
import io.iohk.scevm.consensus.validators.ChainingValidatorImpl
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.BranchProvider.MissingParent
import io.iohk.scevm.db.storage.{BlockchainStorage, BlocksWriter}
import io.iohk.scevm.domain.{ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.testing.BlockGenerators.{
  obftBlockGen,
  obftChainBlockWithGen,
  obftChainBlockWithoutTransactionsGen,
  obftChainHeaderGen,
  obftExtendChain
}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.Outcome
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import BranchService.{BranchFormatFailure, RetrievalError}

class BranchServiceSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "BranchService" when {
    "getBranch" should {
      "fail when fetching a one element branch" in { fixture =>
        // Insert a block in empty storage
        val block = obftBlockGen.sample.get

        (fixture.blocksWriter.insertBlock(block) >> fixture.branchService.getBranchAndValidate(
          block.header,
          block.header
        )).attempt.ioValue shouldBe Left(BranchFormatFailure(block.header, block.header))
      }

      "pass for any valid branch" in { fixture =>
        forAll(obftChainHeaderGen(2, 20)) { chainOfHeaders =>
          val chain = chainOfHeaders.map(header => ObftBlock(header, ObftBody(Nil)))
          (chain.traverse(block => fixture.blocksWriter.insertBlock(block)) >>

            fixture.branchService.getBranchAndValidate(chain.last.header, chain.head.header)).ioValue shouldBe
            Right(listToBranch(chainOfHeaders))
        }
      }

      "return the missing parent hash if the chain is incomplete" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(2, 20)) { chainOfBlocks =>
          val randomIndex  = scala.util.Random.nextInt(chainOfBlocks.length - 1)
          val missingBlock = chainOfBlocks(randomIndex)
          val orphan       = chainOfBlocks(randomIndex + 1)

          val incompleteChain = chainOfBlocks.filterNot(_ == missingBlock)
          (incompleteChain.traverse(block => fixture.blocksWriter.insertBlock(block)) >>

            fixture.branchService
              .getBranchAndValidate(chainOfBlocks.last.header, chainOfBlocks.head.header)).ioValue shouldBe
            Left(RetrievalError(MissingParent(missingBlock.hash, missingBlock.number, orphan.header)))
        }
      }
    }

    "withSuffixes" should {
      "return the branch when there is no suffix" in { fixture =>
        forAll(obftChainHeaderGen(2, 20)) { chainOfHeaders =>
          val chain  = chainOfHeaders.map(header => ObftBlock(header, ObftBody(Nil)))
          val branch = listToBranch(chainOfHeaders)

          val test = for {
            _   <- chain.traverse(block => fixture.blocksWriter.insertBlock(block))
            res <- fixture.branchService.withSuffixes(branch)
          } yield res shouldBe Seq(branch)

          test.ioValue
        }
      }

      "return the branches with suffix when there are suffixes" in { fixture =>
        forAll(obftChainHeaderGen(2, 20)) { chainOfHeaders =>
          val suffix1 = obftExtendChain(chainOfHeaders, 3).sample.get
          val suffix2 = obftExtendChain(chainOfHeaders, 5).sample.get

          val chain   = (suffix1 ++ suffix2).distinct.map(header => ObftBlock(header, ObftBody(Nil)))
          val branch  = listToBranch(chainOfHeaders)
          val branch1 = listToBranch(suffix1)
          val branch2 = listToBranch(suffix2)

          val test = for {
            _   <- chain.traverse(block => fixture.blocksWriter.insertBlock(block))
            res <- fixture.branchService.withSuffixes(branch)
          } yield res shouldBe Seq(branch1, branch2)

          test.ioValue
        }
      }
    }

    "toBlocksBranch" should {
      "return a branch of blocks" in { fixture =>
        val chainOfBlocks = obftChainBlockWithGen(2, 20, obftBlockGen).sample.get
        val headersBranch = listToBranch(chainOfBlocks.map(_.header))

        val test = for {
          // insert blocks
          _            <- chainOfBlocks.traverse(fixture.blocksWriter.insertBlock)
          blocksBranch <- fixture.branchService.toBlocksBranch(headersBranch)
        } yield assert(blocksBranch.contains(listToBranch(chainOfBlocks)))

        test.ioValue
      }
    }

  }

  private def listToBranch(list: List[ObftHeader]): HeadersBranch = {
    assert(list.length >= 2)
    HeadersBranch(list.head, list.tail.init.toVector, list.last)
  }

  private def listToBranch(list: List[ObftBlock]): BlocksBranch = {
    assert(list.length >= 2)
    BlocksBranch(list.head, list.tail.init.toVector, list.last)
  }

  /* Fixture */

  case class FixtureParam(
      blocksWriter: BlocksWriter[IO],
      branchService: BranchService[IO]
  )
  override protected def withFixture(test: OneArgTest): Outcome = {
    val blockchainStorage = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
    val branchService     = new BranchServiceImpl[IO](blockchainStorage, blockchainStorage)(new ChainingValidatorImpl)

    test(FixtureParam(blockchainStorage, branchService))
  }
}
