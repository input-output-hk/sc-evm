package io.iohk.scevm.db.storage

import cats.effect.IO
import cats.implicits.toTraverseOps
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{BlocksDistance, ObftBlock, ObftBody}
import io.iohk.scevm.testing.BlockGenerators.obftChainHeaderGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class GetByNumberServiceSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  "Get hash by number" should {
    "be equivalent to search in the list of headers" in {
      val chainSize = 10
      forAll(obftChainHeaderGen(1, chainSize), Gen.choose(-1, chainSize + 2)) { (chain, random) =>
        val dataSource = EphemDataSource()

        val number = chain.head.number + BlocksDistance(random)

        val blockchainStorage = BlockchainStorage.unsafeCreate[IO](dataSource)

        val stableNumberMappingStorage = new StableNumberMappingStorageImpl[IO](dataSource)
        val appStorage                 = ObftAppStorage.unsafeCreate[IO](dataSource)

        val stableBlock = ObftBlock(chain(chain.length / 2), ObftBody.empty)

        (for {
          _ <- chain.traverse(header => blockchainStorage.insertBlock(ObftBlock(header, ObftBody.empty)))
          _ <-
            IO(
              chain.reverse
                .drop(random)
                .foreach(header => stableNumberMappingStorage.put(header.number, header.hash).commit())
            )
          _      <- appStorage.putLatestStableBlockHash(stableBlock.hash)
          tested <- IO.delay(new GetByNumberServiceImpl(blockchainStorage, stableNumberMappingStorage))
          test <-
            tested.getHashByNumber(chain.last.hash)(number).map(_ shouldBe chain.find(_.number == number).map(_.hash))
        } yield test).ioValue
      }
    }
  }

}
