package io.iohk.scevm.db.storage

import cats.effect.IO
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.testing.BlockGenerators.{obftBlockHeaderGen, obftChainHeaderGen}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ReverseChainStorageSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "ReverseChainStorage" when {
    "storing children" should {
      "list multiple children under a single parent's key" in {
        forAll(obftBlockHeaderGen, obftChainHeaderGen(1, 100)) { (parent, blocks) =>
          val reverseChainStorage = new ReverseChainStorageImpl[IO](EphemDataSource())
          val children            = blocks.map(_.copy(parentHash = parent.hash, number = parent.number.next))

          children.foreach(child => reverseChainStorage.insertChildHeaderWithoutCommit(child).map(_.commit()).ioValue)
          reverseChainStorage.getChildren(children.head.parentHash).ioValue shouldBe children
        }
      }

      "not store duplicates" in {
        forAll(obftBlockHeaderGen) { header =>
          val reverseChainStorage = new ReverseChainStorageImpl[IO](EphemDataSource())

          // Insert once
          reverseChainStorage.insertChildHeaderWithoutCommit(header).map(_.commit()).ioValue
          reverseChainStorage.getChildren(header.parentHash).ioValue shouldBe List(header)

          // Insert twice
          reverseChainStorage.insertChildHeaderWithoutCommit(header).map(_.commit()).ioValue
          reverseChainStorage.getChildren(header.parentHash).ioValue shouldBe List(header)
        }
      }
    }
  }
}
