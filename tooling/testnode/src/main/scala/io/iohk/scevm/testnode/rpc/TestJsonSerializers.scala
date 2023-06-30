package io.iohk.scevm.testnode.rpc

import io.iohk.scevm.rpc.serialization.JsonSerializers
import io.iohk.scevm.rpc.serialization.JsonSerializers.ByteStringSerializer
import io.iohk.scevm.testnode.TestJRpcController.{AddressHash, BlockCount, ChainParams, Timestamp}
import io.iohk.scevm.testnode.TestJsonMethodsImplicits
import org.json4s.JsonAST.{JObject, JValue}
import org.json4s.{CustomSerializer, Formats, JInt, JLong}

object TestJsonSerializers {

  implicit lazy val formats: Formats =
    JsonSerializers.formats +
      BlockCountSerializer +
      TimestampSerializer +
      ChainParamsSerializer +
      AddressHashSerializer

  object BlockCountSerializer
      extends CustomSerializer[BlockCount](_ =>
        (
          { case JInt(i) => BlockCount(i.toInt) },
          PartialFunction.empty
        )
      )

  object TimestampSerializer
      extends CustomSerializer[Timestamp](_ =>
        (
          {
            case JLong(l) => Timestamp(l)
            case JInt(i)  => Timestamp(i.toLong)
          },
          PartialFunction.empty
        )
      )

  object ChainParamsSerializer
      extends CustomSerializer[ChainParams](_ =>
        (
          Function
            .unlift((json: JObject) => TestJsonMethodsImplicits.extractChainParam(json).toOption)
            .compose[JValue] { case obj: JObject => obj },
          PartialFunction.empty
        )
      )

  object AddressHashSerializer
      extends CustomSerializer[AddressHash](_ =>
        (
          ByteStringSerializer.deserializePartial(formats).andThen(AddressHash),
          PartialFunction.empty
        )
      )
}
