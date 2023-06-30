package io.iohk.scevm.testnode.rpc.armadillo.test

import io.iohk.armadillo._
import io.iohk.armadillo.json.json4s._
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import io.iohk.scevm.testnode.TestJRpcController.{BlockCount, ChainParams, Timestamp}
import io.iohk.scevm.testnode.rpc.TestJsonSerializers
import io.iohk.scevm.testnode.rpc.TestRpcSchemas._
import org.json4s.Formats
import sttp.tapir.Validator

object TestApi extends JsonMethodsImplicits {
  implicit override val formats: Formats = TestJsonSerializers.formats

  val test_mineBlocks: JsonRpcEndpoint[BlockCount, JsonRpcError[Unit], Boolean] =
    jsonRpcEndpoint(m"test_mineBlocks")
      .in(param[BlockCount]("blockCount"))
      .out[Boolean]("result")
      .errorOut(errorNoData)

  val test_modifyTimestamp: JsonRpcEndpoint[Timestamp, JsonRpcError[Unit], Boolean] =
    jsonRpcEndpoint(m"test_modifyTimestamp")
      .in(param[Timestamp]("timestamp"))
      .out[Boolean]("result")
      .errorOut(errorNoData)

  val test_rewindToBlock: JsonRpcEndpoint[BlockNumber, JsonRpcError[Unit], Boolean] =
    jsonRpcEndpoint(m"test_rewindToBlock")
      .in(param[BlockNumber]("blockNumber").validate(Validator.positiveOrZero))
      .out[Boolean]("result")
      .errorOut(errorNoData)

  val test_setChainParams: JsonRpcEndpoint[ChainParams, JsonRpcError[Unit], Boolean] =
    jsonRpcEndpoint(m"test_setChainParams")
      .in(param[ChainParams]("chainParams"))
      .out[Boolean]("result")
      .errorOut(errorNoData)
}
