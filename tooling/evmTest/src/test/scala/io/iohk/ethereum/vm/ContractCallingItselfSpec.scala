package io.iohk.ethereum.vm

import io.iohk.ethereum.vm.utils.EvmTestEnv
import io.iohk.scevm.domain.UInt256
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

// scalastyle:off magic.number
class ContractCallingItselfSpec extends AnyFreeSpec with Matchers {

  "EVM running ContractCallingItself contract" - {

    "should handle a call to itself" in new EvmTestEnv {
      val (_, contract) = deployContract("ContractCallingItself")

      contract.getSomeVar().call().returnData shouldBe UInt256(10).bytes

      val result = contract.callSelf().call()
      result.error shouldBe None

      contract.getSomeVar().call().returnData shouldBe UInt256(20).bytes
    }
  }

}
