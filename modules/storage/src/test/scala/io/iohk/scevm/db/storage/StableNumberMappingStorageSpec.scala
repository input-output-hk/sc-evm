package io.iohk.scevm.db.storage

import cats.effect.IO
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.testing.BlockGenerators.obftChainHeaderGen
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience}
import org.scalatest.Assertions
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StableNumberMappingStorageSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  "StableNumberMappingStorage" when {
    "Initialization" should {
      "ensure that genesis is defined in the mapping" in {
        val genesis = BlockGenerators.obftBlockGen.sample.get
        val storage = new StableNumberMappingStorageImpl[IO](EphemDataSource())

        StableNumberMappingStorage
          .init[IO](storage)(genesis)
          .map(_ =>
            if (storage.get(genesis.number).isEmpty) Assertions.fail("value should have been stored")
            else Assertions.succeed
          )
          .ioValue
      }
    }

    "Mapping" should {
      "write and read" in {
        forAll(obftChainHeaderGen(1, 10)) { branch =>
          val storage = new StableNumberMappingStorageImpl[IO](EphemDataSource())
          branch.foreach(header => storage.put(header.number, header.hash).commit())
          branch.foreach(header => storage.get(header.number) shouldBe Some(header.hash))
        }
      }
    }
  }

}
