package io.iohk.scevm.consensus.pos

import cats.effect.IO
import fs2.concurrent.SignallingRef
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.db.storage.{BlockchainStorage, DataSourceFixture}
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.testing.BlockGenerators.{obftBlockGen, obftBlockHeaderGen}
import io.iohk.scevm.testing.{IOSupport, NormalPatience, fixtures}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CurrentBranchSpec
    extends FixtureAnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with DataSourceFixture
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "CurrentBranch.bestHash" should {
    "return the stable block hash at initialisation" in { fixture =>
      import fixture._
      CurrentBranch.bestHash[IO].ioValue shouldEqual stableBlock.hash
    }

    "return the new reference to the best block" in { fixture =>
      import fixture._
      val newBest      = obftBlockHeaderGen.sample.get
      val betterBranch = ConsensusService.BetterBranch(Vector.empty, newBest)
      (currentBranch.update(_.update(betterBranch)) >>
        CurrentBranch.bestHash[IO]).ioValue shouldEqual newBest.hash
    }
  }

  "CurrentBranch.stableHash" should {
    "return the stable block hash at initialisation" in { fixture =>
      import fixture._
      CurrentBranch.stableHash[IO].ioValue shouldEqual stableBlock.hash
    }

    "return the new reference to the stable block" in { fixture =>
      import fixture._
      val newStableBlock = obftBlockGen.sample.get
      (for {
        best        <- CurrentBranch.best[IO]
        betterBranch = ConsensusService.BetterBranch(Vector(newStableBlock), best)
        _           <- currentBranch.update(_.update(betterBranch))
        stable      <- CurrentBranch.stableHash[IO]
      } yield stable).ioValue shouldEqual newStableBlock.hash
    }
  }

  case class FixtureParam(stableBlock: ObftBlock)(implicit val currentBranch: SignallingRef[IO, CurrentBranch])

  override def initFixture(dataSource: DataSource): FixtureParam = {
    val blockchainStorage = BlockchainStorage.unsafeCreate[IO](dataSource)

    val stableBlock: ObftBlock = fixtures.GenesisBlock.block

    implicit val currentBranch: SignallingRef[IO, CurrentBranch] = (
      blockchainStorage.insertBlock(stableBlock) >>
        SignallingRef[IO].of(CurrentBranch(stableBlock.header))
    ).ioValue

    FixtureParam(stableBlock)
  }
}
