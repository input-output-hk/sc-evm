package io.iohk.scevm.db.storage

import cats.effect.IO
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.db.storage.BranchProvider.MissingParent
import io.iohk.scevm.testing.BlockGenerators.{obftChainBlockWithoutTransactionsGen, obftChainHeaderGen}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class AlternativeBlockHeadersStorageSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with MockFactory
    with DataSourceFixture
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "Fetching a branch" when {
    "connected" should {
      "return the branch" in { fixture =>
        forAll(obftChainHeaderGen(1, 10)) { branch =>
          branch.foreach(header => fixture.alternativeBlockHeadersStorage.put(header).commit())

          val tip    = branch.last
          val length = scala.util.Random.nextInt(branch.length)
          fixture.alternativeBlockHeadersStorage.getBranch(tip, length).ioValue shouldBe Right(branch.takeRight(length))
        }
      }
    }

    "disconnected" should {
      "return the missing hash" in { fixture =>
        forAll(obftChainBlockWithoutTransactionsGen(2, 10)) { branch =>
          val randomIndex = scala.util.Random.nextInt(branch.length - 1)
          val missing     = branch(randomIndex)
          val ancestor    = branch(randomIndex + 1)

          // Store the incomplete branch
          branch.foreach { block =>
            if (block.hash == missing.hash) ()
            else fixture.alternativeBlockHeadersStorage.put(block.header).commit()
          }

          val tip = branch.lastOption.getOrElse(missing)
          fixture.alternativeBlockHeadersStorage.getBranch(tip.header, branch.length).ioValue shouldBe
            Left(MissingParent(missing.hash, missing.number, ancestor.header))
        }
      }
    }
  }

  /* Fixture below */

  case class FixtureParam(alternativeBlockHeadersStorage: AlternativeBlockHeadersStorage[IO])
  override def initFixture(dataSource: DataSource): FixtureParam = {
    val alternativeBlockHeadersStorage = new AlternativeBlockHeadersStorageImpl[IO](dataSource)

    FixtureParam(alternativeBlockHeadersStorage)
  }

}
