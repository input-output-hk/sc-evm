package io.iohk.scevm.testnode.rpc.armadillo.hardhat

import io.iohk.armadillo.JsonRpcError.NoData
import io.iohk.armadillo._
import io.iohk.armadillo.json.json4s._
import io.iohk.scevm.domain.{Address, MemorySlot}
import io.iohk.scevm.rpc.armadillo.CommonRpcSchemas._
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import sttp.tapir.Validator

object HardhatApi extends JsonMethodsImplicits {

  private val addressParam: JsonRpcInput.Basic[Address]       = param[Address]("address")
  private val memorySlotParam: JsonRpcInput.Basic[MemorySlot] = param[MemorySlot]("memorySlot")
  private val valueParam: JsonRpcInput.Basic[BigInt]          = param[BigInt]("token").validate(Validator.positiveOrZero)

  val hardhat_setStorageAt: JsonRpcEndpoint[(Address, MemorySlot, BigInt), NoData, Boolean] =
    jsonRpcEndpoint(m"hardhat_setStorageAt")
      .in(addressParam.and(memorySlotParam).and(valueParam))
      .out[Boolean]("GetBlockByHashResponse")
      .errorOut(errorNoData)
}
