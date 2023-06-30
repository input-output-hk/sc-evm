package io.iohk.scevm.rpc.armadillo.inspect

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.domain.BlockOffset
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.InspectController.GetChainResponse

// scalastyle:off line.size.limit
object InspectApi extends CommonApi {

  val inspect_getChain: JsonRpcEndpoint[Option[BlockOffset], JsonRpcError[Unit], GetChainResponse] =
    baseEndpoint(
      "inspect_getChain",
      """Returns all the chains that start from a block defined by an offset. This offset represents the distance from the current best block to where we would like to see the chain structure.
        |If no offset is provided, the target block will be the current stable block.
        |_Note: if the offset is too big, an OutOfMemory error may be raised._
        |""".stripMargin
    )
      .in(input[Option[BlockOffset]]("offset"))
      .out(output[GetChainResponse]("GetChainResponse"))
      .errorOut(errorNoData)
}
// scalastyle:on line.size.limit
