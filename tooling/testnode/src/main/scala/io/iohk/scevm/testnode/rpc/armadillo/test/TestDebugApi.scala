package io.iohk.scevm.testnode.rpc.armadillo.test

import io.iohk.armadillo._
import io.iohk.armadillo.json.json4s._
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, TransactionIndex}
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits
import io.iohk.scevm.testnode.TestJRpcController._
import io.iohk.scevm.testnode.rpc.TestJsonSerializers
import io.iohk.scevm.testnode.rpc.TestRpcSchemas._
import org.json4s.Formats
import sttp.tapir.Validator

object TestDebugApi extends JsonMethodsImplicits {
  implicit override val formats: Formats = TestJsonSerializers.formats

  private type debugAccountRangeInputs   = (Either[BlockNumber, BlockHash], TransactionIndex, AddressHash, Int)
  private type debugStorageRangeAtInputs = (Either[BlockNumber, BlockHash], TransactionIndex, Address, AddressHash, Int)

  val debug_accountRange: JsonRpcEndpoint[debugAccountRangeInputs, JsonRpcError[Unit], AccountsInRangeResponse] =
    jsonRpcEndpoint(m"debug_accountRange")
      .in(
        param[Either[BlockNumber, BlockHash]]("blockHashOrNumber")
          .and(param[TransactionIndex]("TransactionIndex"))
          .and(param[AddressHash]("AddressHash"))
          .and(param[Int]("maxResults").validate(Validator.positive))
      )
      .out[AccountsInRangeResponse]("AccountsInRangeResponse")
      .errorOut(errorNoData)

  val debug_storageRangeAt: JsonRpcEndpoint[debugStorageRangeAtInputs, JsonRpcError[Unit], StorageRangeResponse] =
    jsonRpcEndpoint(m"debug_storageRangeAt")
      .in(
        param[Either[BlockNumber, BlockHash]]("blockHashOrNumber")
          .and(param[TransactionIndex]("TransactionIndex"))
          .and(param[Address]("Address"))
          .and(param[AddressHash]("Begin"))
          .and(param[Int]("maxResults").validate(Validator.positive))
      )
      .out[StorageRangeResponse]("StorageRangeResponse")
      .errorOut(errorNoData)

}
