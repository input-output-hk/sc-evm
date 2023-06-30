package io.iohk.scevm.rpc.armadillo.debug

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, errorNoData}
import io.iohk.scevm.domain.TransactionHash
import io.iohk.scevm.rpc.armadillo.CommonApi
import io.iohk.scevm.rpc.controllers.DebugController.{TraceParameters, TraceTransactionResponse}

object DebugApi extends CommonApi {

  // scalastyle:off line.size.limit
  val debug_traceTransaction
      : JsonRpcEndpoint[(TransactionHash, TraceParameters), JsonRpcError[Unit], TraceTransactionResponse] =
    baseEndpoint(
      "debug_traceTransaction",
      """Re-executes a transaction that was already present in a block, with the same conditions as when the transaction was initially added to the blockchain (previous transactions in the block will also be re-executed).
        |A JavaScript-based tracer must be defined, and an optional timeout may be specified to override the default timeout.
        |
        |The JavaScript-based tracer must define some functions:
        |- `result`: called at the end, and contains the result of the tracing.
        |- `fault`: called when an error occurs.
        |- `step`: called for each step of the EVM
        |- `enter`: called before executing CALL, CREATE, or SELFDESTRUCT opcode.
        |- `exit`: called after executing CALL, CREATE, or SELFDESTRUCT opcode.
        |Note that `enter` and `exit` must always be used together, and either `step` or the couple `enter`/`exit` must be defined.
        |
        |Custom helper methods can also be defined.
        |
        |All of the predefined functions take specific objects as arguments, described below:
        |- `result`: `ctx` and `db`
        |- `fault`: `log` and `db`
        |- `step`: `log` and `db`
        |- `enter`: callFrame
        |- `exit`: frameResult
        |
        |For more details, about the arguments, see https://geth.ethereum.org/docs/rpc/ns-debug#javascript-based-tracing""".stripMargin
    )
      .in(
        input[TransactionHash]("transactionHash")
          .and(input[TraceParameters]("TraceParameters"))
      )
      .out(output[TraceTransactionResponse]("TraceTransactionResponse"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
