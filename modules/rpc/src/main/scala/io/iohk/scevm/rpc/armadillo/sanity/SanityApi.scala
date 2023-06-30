package io.iohk.scevm.rpc.armadillo.sanity
import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.SanityController.{CheckChainConsistencyRequest, CheckChainConsistencyResponse}

object SanityApi extends CommonApi {

  // scalastyle:off line.size.limit
  val sanity_checkChainConsistency
      : JsonRpcEndpoint[CheckChainConsistencyRequest, JsonRpcError[Unit], CheckChainConsistencyResponse] =
    baseEndpoint(
      "sanity_checkChainConsistency",
      "Performs some checks on each block between `from` (defaults to `0x0` if not provided) and `to` (defaults to stable block's number if not provided). Various validations are performed: is the block hash available in the storage, are the block's number and hash consistent with the previous/next block, etc. A list of errors (if any) is then returned."
    )
      .in(input[CheckChainConsistencyRequest]("CheckChainConsistencyRequest"))
      .out(output[CheckChainConsistencyResponse]("CheckChainConsistencyResponse"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
