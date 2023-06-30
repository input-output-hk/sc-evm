package io.iohk.scevm.domain

import io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ObftHeaderSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  "RLP encoding of the ObftHeader" should "be symmetric" in {
    import io.iohk.ethereum.rlp._
    forAll(obftBlockHeaderGen) { blockHeader: ObftHeader =>
      val encoded: Array[Byte] = encode(blockHeader)
      val decoded              = decode[ObftHeader](encoded)

      decoded shouldBe blockHeader
    }
  }

  "Show instance" should "print the expected format" in {
    import cats.implicits.toShow
    val block = obftBlockHeaderGen.apply(Gen.Parameters.default, Seed(1000L)).get
    block.show shouldBe "BlockTag(number=111780503902070228939280597878741991344113852792903244476957174904930315788164, slot=71377098127192621694477867588821605272066937576411096128079886702195064537855, hash=02d883afa89c7237922245781579a7bc1ea71063b022c02db87fae0d0cdfdb6a)"
  }

}
