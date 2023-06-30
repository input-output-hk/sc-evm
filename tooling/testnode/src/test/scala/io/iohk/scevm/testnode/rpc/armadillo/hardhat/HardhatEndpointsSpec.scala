package io.iohk.scevm.testnode.rpc.armadillo.hardhat

import io.circe.literal.JsonStringContext
import io.iohk.scevm.domain.{Address, MemorySlot}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import org.json4s.JArray
import org.json4s.JsonAST.JString

class HardhatEndpointsSpec extends ArmadilloScenarios {
  "hardhat_setStorageAt" shouldPass new EndpointScenarios(HardhatApi.hardhat_setStorageAt) {
    val address    = JString("0x0d2026b3EE6eC71FC6746ADb6311F6d3Ba1C000B")
    val memorySlot = JString("0x0")
    val value      = JString("0x0000000000000000000000000000000000000000000000000000000000000001")

    val expectedInputs = (Address("0d2026b3EE6eC71FC6746ADb6311F6d3Ba1C000B"), MemorySlot(0), BigInt(1))

    "should accept correct inputs" in successScenario(
      params = JArray(List(address, memorySlot, value)),
      expectedInputs = expectedInputs,
      serviceResponse = true,
      expectedResponse = json"true"
    )
  }
}
