package io.iohk.ethereum.vm

import io.iohk.ethereum.vm.utils.EvmTestEnv
import io.iohk.scevm.domain.UInt256
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

// scalastyle:off magic.number
class CallerSpec extends AnyFreeSpec with Matchers {

  "EVM running Caller contract" - {

    "should handle a call to Callee" in new EvmTestEnv {
      val (_, callee) = deployContract("Callee")
      val (_, caller) = deployContract("Caller")

      val callRes = caller.makeACall(callee.address, 123).call()
      callRes.error shouldBe None

      callee.getFoo().call().returnData shouldBe UInt256(123).bytes
    }
  }

}
