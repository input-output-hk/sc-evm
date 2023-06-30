package io.iohk.scevm.db.storage

import cats.effect.IO
import cats.implicits.toTraverseOps
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.db.storage.BranchProvider.{MissingParent, NotConnected}
import io.iohk.scevm.domain.{ObftBlock, ObftBody}
import io.iohk.scevm.testing.BlockGenerators.{
  obftBlockGen,
  obftChainBlockWithoutTransactionsGen,
  obftChainHeaderGen,
  obftExtendChain
}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BlockchainStorageSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with DataSourceFixture
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "BlockchainStorage" when {
    "be able to store a block" in { fixture =>
      // Insert a block in empty storage
      val block = obftBlockGen.sample.get

      // When storing a block, no exception is raised
      fixture.blockchainStorage.insertBlock(block).attempt.ioValue shouldBe a[Right[_, _]]
    }

    "getBlockHeader" should {
      "return the corresponding block" in { fixture =>
        val block = obftBlockGen.sample.get

        val test = for {
          _   <- fixture.blockchainStorage.insertBlock(block)
          res <- fixture.blockchainStorage.getBlockHeader(block.hash)
        } yield res shouldBe Some(block.header)

        test.ioValue
      }

      "return None if the block is missing" in { fixture =>
        val block = obftBlockGen.sample.get
        fixture.blockchainStorage.getBlockHeader(block.hash).map(_ shouldBe None).ioValue
      }
    }

    "getBlockBody" should {
      "return the corresponding block" in { fixture =>
        val block = obftBlockGen.sample.get

        val test = for {
          _   <- fixture.blockchainStorage.insertBlock(block)
          res <- fixture.blockchainStorage.getBlockBody(block.hash)
        } yield res shouldBe Some(block.body)

        test.ioValue
      }

      "return None if the block is missing" in { fixture =>
        val block = obftBlockGen.sample.get
        fixture.blockchainStorage.getBlockBody(block.hash).map(_ shouldBe None).ioValue
      }
    }

    "getBlock" should {
      "return the corresponding block" in { fixture =>
        val block = obftBlockGen.sample.get

        val test = for {
          _   <- fixture.blockchainStorage.insertBlock(block)
          res <- fixture.blockchainStorage.getBlock(block.hash)
        } yield res shouldBe Some(block)

        test.ioValue
      }

      "return None if the block is missing" in { fixture =>
        val block = obftBlockGen.sample.get
        fixture.blockchainStorage.getBlock(block.hash).map(_ shouldBe None).ioValue
      }
    }

    "fetchChain" should {
      "return the stored chain" in { fixture =>
        forAll(obftChainHeaderGen(2, 20)) { chainOfHeaders =>
          val chain = chainOfHeaders.map(header => ObftBlock(header, ObftBody(Nil)))
          chain.traverse(block => fixture.blockchainStorage.insertBlock(block)).ioValue

          fixture.blockchainStorage
            .fetchChain(chainOfHeaders.last, chainOfHeaders.head)
            .map(_ shouldBe Right(chainOfHeaders))
            .ioValue
        }
      }

      "return the hash of the missing parent" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(2, 20)) { blocks =>
          val index = util.Random.between(0, blocks.length - 1)
          val blocksMinusOne = blocks.zipWithIndex.flatMap {
            case (_, i) if i == index => None
            case (block, _)           => Some(block)
          }
          blocksMinusOne.traverse(block => fixture.blockchainStorage.insertBlock(block)).ioValue

          val droppedBlock  = blocks.apply(index)
          val ancestorBlock = blocks(index + 1)

          fixture.blockchainStorage
            .fetchChain(blocks.last.header, blocks.head.header)
            .map(_ shouldBe Left(MissingParent(droppedBlock.hash, droppedBlock.number, ancestorBlock.header)))
            .ioValue
        }
      }

      "return NotConnected when from and to are impossible to connect" in { fixture =>
        val twoBranchWithSameSize = for {
          chain1 <- obftChainHeaderGen(2, 20)
          chain2 <- obftChainHeaderGen(chain1.size, chain1.size, chain1.head.number)
        } yield (chain1, chain2)

        forAll(twoBranchWithSameSize) { case (chainOfHeaders1, chainOfHeaders2) =>
          // Given two unrelated chains
          val chain1 = chainOfHeaders1.map(header => ObftBlock(header, ObftBody(Nil)))
          val chain2 = chainOfHeaders2.map(header => ObftBlock(header, ObftBody(Nil)))
          chain1.traverse(block => fixture.blockchainStorage.insertBlock(block)).ioValue
          chain2.traverse(block => fixture.blockchainStorage.insertBlock(block)).ioValue

          fixture.blockchainStorage
            .fetchChain(chainOfHeaders1.last, chainOfHeaders2.head)
            .map(_ shouldBe Left(NotConnected))
            .ioValue
        }
      }
    }

    "findSuffixesTip" should {
      "return nothing if there are no children" in { fixture =>
        forAll(obftChainHeaderGen(2, 5)) { chainOfHeaders =>
          fixture.blockchainStorage
            .findSuffixesTips(chainOfHeaders.last.hash)
            .map(_ shouldBe Seq.empty)
            .ioValue
        }
      }

      "return the leaves" in { fixture =>
        val chainOfHeaders = obftChainHeaderGen(2, 2).sample.get
        val extension1     = obftExtendChain(chainOfHeaders, 1).sample.get
        val extension2     = obftExtendChain(chainOfHeaders, 3).sample.get
        val fork2          = obftExtendChain(extension2.init, 3).sample.get
        val extension3     = obftExtendChain(chainOfHeaders, 5).sample.get
        val all =
          (extension1 ++ extension2 ++ fork2 ++ extension3).distinct.map(header => ObftBlock(header, ObftBody.empty))

        val test = for {
          _   <- all.traverse(fixture.blockchainStorage.insertBlock)
          res <- fixture.blockchainStorage.findSuffixesTips(chainOfHeaders.last.hash)
        } yield res shouldBe Seq(extension1.last, extension2.last, fork2.last, extension3.last)

        test.ioValue
      }
    }
  }

  case class FixtureParam(
      blockchainStorage: BlocksReader[IO] with BlocksWriter[IO] with BranchProvider[IO]
  )
  override def initFixture(dataSource: DataSource): FixtureParam = {
    val blockchainStorage = BlockchainStorage.unsafeCreate[IO](dataSource)

    FixtureParam(blockchainStorage)
  }
}
