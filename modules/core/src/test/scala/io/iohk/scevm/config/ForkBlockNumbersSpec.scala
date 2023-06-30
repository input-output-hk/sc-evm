package io.iohk.scevm.config

import io.iohk.scevm.domain.BlockNumber
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ForkBlockNumbersSpec extends AnyWordSpec with Matchers {
  "returns all block numbers" in {
    val config = ForkBlockNumbers(
      BlockNumber(42),
      BlockNumber(43),
      BlockNumber(44),
      BlockNumber(45),
      BlockNumber(46),
      BlockNumber(47),
      BlockNumber(48),
      BlockNumber(49),
      BlockNumber(50),
      BlockNumber(51)
    )
    config.all shouldBe List.iterate(BlockNumber(42), 10)(_.next)
  }
}
