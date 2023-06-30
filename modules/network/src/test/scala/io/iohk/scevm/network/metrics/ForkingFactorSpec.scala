package io.iohk.scevm.network.metrics

import cats.effect.IO
import io.iohk.scevm.domain.{BlockNumber, ObftBlock, ObftHeader}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ForkingFactorSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with NormalPatience
    with IOSupport
    with MockFactory {

  val stabilityParameter               = 5
  val blockProvider: BlockProvider[IO] = mock[BlockProvider[IO]]
  val forkingFactor: ForkingFactorImpl[IO] =
    ForkingFactorImpl[IO](blockProvider, stabilityParameter).ioValue

  val random0                = BlockGenerators.obftBlockGen.sample.get
  val stableBlock: ObftBlock = random0.copy(header = random0.header.copy(number = BlockNumber(5)))

  val random1 = BlockGenerators.obftBlockHeaderGen.sample.get
  val random2 = BlockGenerators.obftBlockHeaderGen.sample.get
  val random3 = BlockGenerators.obftBlockHeaderGen.sample.get
  val random4 = BlockGenerators.obftBlockHeaderGen.sample.get
  val random5 = BlockGenerators.obftBlockHeaderGen.sample.get
  val random6 = BlockGenerators.obftBlockHeaderGen.sample.get

  val header4: ObftHeader  = random1.copy(number = BlockNumber(4))
  val header7: ObftHeader  = random2.copy(number = BlockNumber(7))
  val header8a: ObftHeader = random3.copy(number = BlockNumber(8))
  val header8b: ObftHeader = random4.copy(number = BlockNumber(8))
  val header9: ObftHeader  = random5.copy(number = BlockNumber(9))
  val header10: ObftHeader = random6.copy(number = BlockNumber(10))
  val header11: ObftHeader = random6.copy(number = BlockNumber(11))

  "ForkingFactor" should "aggregate block headers by block number" in {
    (() => blockProvider.getStableBlockHeader).expects().returns(IO.pure(stableBlock.header)).anyNumberOfTimes()

    (for {
      _ <- forkingFactor.evaluate(header4)
      _ <- forkingFactor.evaluate(header7)
      _ <- forkingFactor.evaluate(header8a)
      _ <- forkingFactor.evaluate(header8b)
    } yield ()).unsafeRunSync()

    val blocksMap = forkingFactor.hashToNumberMap.get.unsafeRunSync()
    blocksMap.keys.toList shouldBe List(header7.number, header8a.number)
    blocksMap.values.toList.flatten shouldBe List(header7.hash, header8a.hash, header8b.hash)

    val ff = ForkingFactor.calculateForkingFactor(forkingFactor.hashToNumberMap.get.unsafeRunSync(), stabilityParameter)
    ff shouldBe 0.6

    // Add more blocks and see forking factor updating
    (for {
      _ <- forkingFactor.evaluate(header9)
      _ <- forkingFactor.evaluate(header10)
      _ <- forkingFactor.evaluate(header11) // It's above best, but it will still be taken into account
    } yield ()).unsafeRunSync()

    val ff2 =
      ForkingFactor.calculateForkingFactor(forkingFactor.hashToNumberMap.get.unsafeRunSync(), stabilityParameter)
    ff2 shouldBe 1.2
  }
}
