package io.iohk.scevm.consensus.pos

import cats.effect.IO
import cats.implicits.toTraverseOps
import fs2.concurrent.SignallingRef
import io.iohk.scevm.consensus.domain.{BranchServiceImpl, HeadersBranch}
import io.iohk.scevm.consensus.validators.ChainingValidatorImpl
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{BlockchainStorage, BlocksWriter}
import io.iohk.scevm.domain.{ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.testing.BlockGenerators.{obftBlockGen, obftBlockHeaderGen, obftChainHeaderGen, obftExtendChain}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.Outcome
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BranchResolutionSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "BranchResolution" when {

    "getCandidateBranches" should {
      "(impact another branch than best) return the branch" in { fixture =>
        forAll(obftChainHeaderGen(2, 20)) { chain =>
          val best     = obftBlockHeaderGen.sample.get.copy(number = chain.head.number.previous) // any best
          val stable   = chain.head
          val newBlock = chain.last

          val test = for {
            _ <-
              (best +: chain).traverse(header =>
                fixture.blocksWriter.insertBlock(ObftBlock(header, ObftBody(Seq.empty)))
              )
            _          <- fixture.current.set(CurrentBranch(stable, best))
            candidates <- fixture.branchResolution.getCandidateBranches(best, stable, newBlock)
            res         = candidates.toOption.get
          } yield res shouldEqual List(listToBranch(chain))

          test.ioValue
        }
      }

      "(extending) return the extended branch starting from the ancestor" in { fixture =>
        forAll(obftChainHeaderGen(2, 20)) { chain =>
          val stable   = chain.head
          val best     = chain.init.last
          val newBlock = chain.last

          val test = for {
            _          <- chain.traverse(header => fixture.blocksWriter.insertBlock(ObftBlock(header, ObftBody(Seq.empty))))
            _          <- fixture.current.set(CurrentBranch(stable, best))
            candidates <- fixture.branchResolution.getCandidateBranches(best, stable, newBlock)
            res         = candidates.toOption.get
          } yield res shouldEqual List(listToBranch(chain))

          test.ioValue
        }
      }

      "(extending with suffixes) return the extended branch starting from ancestor" in { fixture =>
        forAll(obftChainHeaderGen(4, 10)) { chain =>
          val stable   = chain.head
          val best     = chain.reverse.tail.head // parent of last
          val newBlock = chain.last

          val suffix = obftExtendChain(List(best, newBlock), 3).sample.get
          val fork   = obftExtendChain(List(best, newBlock), 3).sample.get

          val expected = List(listToBranch(chain.dropRight(2) ++ suffix), listToBranch(chain.dropRight(2) ++ fork))
            .sortBy(_.tip.number)

          val test = for {
            _ <- (chain ++ suffix ++ fork).traverse(header =>
                   fixture.blocksWriter.insertBlock(ObftBlock(header, ObftBody(Seq.empty)))
                 )
            _          <- fixture.current.set(CurrentBranch(stable, best))
            candidates <- fixture.branchResolution.getCandidateBranches(best, stable, newBlock)
            res         = candidates.toOption.get.sortBy(_.tip.number)
          } yield res shouldBe expected

          test.ioValue
        }
      }

      "(not extending - with suffixes) return the extended branch starting from best" in { fixture =>
        forAll(obftChainHeaderGen(3, 10)) { chain =>
          val stable   = chain.head
          val best     = chain.head
          val newBlock = chain.last

          val fork1 = obftExtendChain(chain, 3).sample.get
          val fork2 = obftExtendChain(chain, 5).sample.get

          val test = for {
            _ <- (fork1 ++ fork2).distinct.traverse(header =>
                   fixture.blocksWriter.insertBlock(ObftBlock(header, ObftBody(Seq.empty)))
                 )
            _          <- fixture.current.set(CurrentBranch(stable, best))
            candidates <- fixture.branchResolution.getCandidateBranches(best, stable, newBlock)
            res         = candidates.toOption.get
          } yield res shouldEqual List(listToBranch(fork1), listToBranch(fork2))

          test.ioValue
        }
      }
    }
  }

  private def listToBranch(list: List[ObftHeader]): HeadersBranch = {
    assert(list.length >= 2)
    HeadersBranch(list.head, list.tail.init.toVector, list.last)
  }

  /* Fixture */

  case class FixtureParam(
      branchResolution: BranchResolutionImpl[IO],
      blocksWriter: BlocksWriter[IO]
  )(implicit val current: SignallingRef[IO, CurrentBranch])
  override protected def withFixture(test: OneArgTest): Outcome = {
    val blockchainStorage      = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
    val stableBlock: ObftBlock = obftBlockGen.sample.get

    implicit val current: SignallingRef[IO, CurrentBranch] = (
      blockchainStorage.insertBlock(stableBlock) >>
        SignallingRef[IO].of(CurrentBranch(stableBlock.header))
    ).ioValue

    val branchService = new BranchServiceImpl[IO](blockchainStorage, blockchainStorage)(new ChainingValidatorImpl)

    val branchResolution =
      new BranchResolutionImpl(
        branchService,
        SimplestHeaderComparator
      )

    test(FixtureParam(branchResolution, blockchainStorage))
  }
}
