package io.iohk.scevm.network

import cats.effect.IO
import cats.syntax.all._
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{GetByNumberService, GetByNumberServiceImpl, ObftStorageBuilder}
import io.iohk.scevm.domain.{BlockNumber, ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.BlockGenerators.obftChainHeaderGen
import io.iohk.scevm.testing.{Generators, IOSupport, NormalPatience}
import org.scalacheck.Arbitrary
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class NetworkModuleStorageImplSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  private val CHAIN_SIZE = 128

  "Get hashes by range" should "returns up to the number of requested entries" in setupBlockchainHeaders {
    case (headerChain, serviceIO) =>
      forAll(
        Generators.intGen(0, headerChain.size - 1),
        Generators.intGen(0, headerChain.size - 1),
        Generators.intGen(0, CHAIN_SIZE * 2),
        Generators.intGen(0, CHAIN_SIZE * 2),
        Arbitrary.arbitrary[Boolean]
      ) { case (tipNumber, initialRequestNumber, skip, limit, reverse) =>
        val result = (for {
          getByNumberService <- serviceIO
          hashes <-
            NetworkModuleStorageImpl.getHashesByRange(getByNumberService)(headerChain(tipNumber))(
              BlockNumber(initialRequestNumber),
              skip,
              limit,
              reverse
            )
        } yield hashes).ioValue
        result.size should be <= limit
      }
  }

  it should "ignore entries after end of chain" in setupBlockchainHeaders { case (headerChain, serviceIO) =>
    forAll(Generators.intGen(0, CHAIN_SIZE * 2), Generators.intGen(0, CHAIN_SIZE * 2)) { case (skip, limit) =>
      val result = (for {
        getByNumberService <- serviceIO
        // ask for latest header number and after
        hashes <-
          NetworkModuleStorageImpl
            .getHashesByRange(getByNumberService)(headerChain.last)(headerChain.last.number, skip, limit, false)
      } yield hashes).ioValue
      val expected = limit match {
        case 0 => List.empty
        case _ => List(headerChain.last.hash)
      }
      result shouldBe expected
    }
  }

  it should "ignore entries before genesis" in setupBlockchainHeaders { case (headerChain, serviceIO) =>
    forAll(Generators.intGen(0, CHAIN_SIZE * 2), Generators.intGen(0, CHAIN_SIZE * 2)) { case (skip, limit) =>
      val result = (for {
        getByNumberService <- serviceIO
        // ask for first header number, and before
        hashes <-
          NetworkModuleStorageImpl
            .getHashesByRange(getByNumberService)(headerChain.last)(headerChain.head.number, skip, limit, true)
      } yield hashes).ioValue
      val expected = limit match {
        case 0 => List.empty
        case _ => List(headerChain.head.hash)
      }
      result shouldBe expected
    }
  }

  it should "return entries without reverse" in setupBlockchainHeaders { case (headerChain, serviceIO) =>
    forAll(Generators.intGen(0, headerChain.size - 1)) { case limit =>
      val result = (for {
        getByNumberService <- serviceIO
        // ask for first header number, and after
        hashes <-
          NetworkModuleStorageImpl
            .getHashesByRange(getByNumberService)(headerChain.last)(headerChain.head.number, 0, limit, false)
      } yield hashes).ioValue
      val expected = headerChain.map(_.hash).take(limit)
      result shouldBe expected
    }
  }

  it should "return entries with reverse" in setupBlockchainHeaders { case (headerChain, serviceIO) =>
    forAll(Generators.intGen(0, headerChain.size - 1)) { case limit =>
      val result = (for {
        getByNumberService <- serviceIO
        // ask for last header number, and before
        hashes <-
          NetworkModuleStorageImpl
            .getHashesByRange(getByNumberService)(headerChain.last)(headerChain.last.number, 0, limit, true)
      } yield hashes).ioValue
      val expected = headerChain.map(_.hash).takeRight(limit).reverse
      result shouldBe expected
    }
  }

  it should "return entries with skip" in setupBlockchainHeaders { case (headerChain, serviceIO) =>
    forAll(Generators.intGen(0, headerChain.size - 1), Generators.intGen(0, headerChain.size - 1)) {
      case (limit, skip) =>
        val result = (for {
          getByNumberService <- serviceIO
          hashes <-
            NetworkModuleStorageImpl
              .getHashesByRange(getByNumberService)(headerChain.last)(headerChain.head.number, skip, limit, false)
        } yield hashes).ioValue
        val expected =
          headerChain.map(_.hash).grouped(skip + 1).map(_.head).take(limit).toList
        result shouldBe expected
    }
  }

  it should "return entries with skip and reverse" in setupBlockchainHeaders { case (headerChain, serviceIO) =>
    forAll(Generators.intGen(0, headerChain.size - 1), Generators.intGen(0, headerChain.size - 1)) {
      case (limit, skip) =>
        val result = (for {
          getByNumberService <- serviceIO
          hashes <-
            NetworkModuleStorageImpl
              .getHashesByRange(getByNumberService)(headerChain.last)(headerChain.last.number, skip, limit, true)
        } yield hashes).ioValue
        val expected =
          headerChain.reverse.map(_.hash).grouped(skip + 1).map(_.head).take(limit).toList
        result shouldBe expected
    }
  }

  /** Create a valid (numbered from 0, with proper parent hash) header chain,
    * store it, and build a GetByNumberService over it.
    * Call the passed doTest function by providing the proper environment.
    * @param doTest a function to run with the created header chain and GetByNumberService
    */
  private def setupBlockchainHeaders(
      doTest: (List[ObftHeader], IO[GetByNumberService[IO]]) => Unit
  ): Unit = {
    val storageMetrics = NoOpStorageMetrics[IO]()
    forAll(obftChainHeaderGen(2, CHAIN_SIZE, BlockNumber(0)).suchThat(_.nonEmpty)) { headersChain =>
      forAll(Generators.intGen(0, headersChain.size - 1), minSuccessful(1)) { stableBlockNumber =>
        val dataSource  = EphemDataSource()
        val stableBlock = ObftBlock(headersChain(stableBlockNumber), ObftBody.empty)

        val F_getByNumberServiceImpl: IO[GetByNumberService[IO]] = for {
          storageBuilder <- ObftStorageBuilder[IO](dataSource, storageMetrics)
          _ <- headersChain.traverse(header =>
                 storageBuilder.blockchainStorage.insertBlock(ObftBlock(header, ObftBody.empty))
               )
          _ <-
            IO.delay(
              headersChain
                .takeWhile(_.number.value <= stableBlockNumber)
                .foreach(header =>
                  storageBuilder.stableNumberMappingStorage
                    .insertBlockHashWithoutCommit(header.number, header.hash)
                    .commit()
                )
            )
          _ <- storageBuilder.appStorage.putLatestStableBlockHash(stableBlock.hash)
          service <- IO.delay(
                       new GetByNumberServiceImpl(
                         storageBuilder.blockchainStorage,
                         storageBuilder.stableNumberMappingStorage
                       )
                     )
        } yield service
        doTest(headersChain, F_getByNumberServiceImpl)
      }
    }
  }

}
