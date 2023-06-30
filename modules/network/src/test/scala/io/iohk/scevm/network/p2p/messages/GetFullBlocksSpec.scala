package io.iohk.scevm.network.p2p.messages

import io.iohk.scevm.network.p2p.messages.OBFT1.GetFullBlocks
import io.iohk.scevm.testing.{BlockGenerators, Generators}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class GetFullBlocksSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  "GetFullBlocks" should "have the same requestId for the same parameters" in {
    forAll(BlockGenerators.blockHashGen, Generators.intGen) { case (hash, count) =>
      val getFullBlocks1 = GetFullBlocks(hash, count)
      val getFullBlocks2 = GetFullBlocks(hash, count)
      getFullBlocks1.requestId shouldBe getFullBlocks2.requestId
    }
  }

  it should "have a different requestIds for different parameters" in {
    forAll(
      BlockGenerators.blockHashGen,
      Generators.intGen,
      BlockGenerators.blockHashGen,
      Generators.intGen
    ) { case (hash1, count1, hash2, count2) =>
      val getFullBlocks1 = GetFullBlocks(hash1, count1)
      val getFullBlocks2 = GetFullBlocks(hash2, count2)
      getFullBlocks1.requestId shouldNot be(getFullBlocks2.requestId)
    }
  }
}
