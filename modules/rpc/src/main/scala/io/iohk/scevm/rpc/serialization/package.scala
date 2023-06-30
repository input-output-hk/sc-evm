package io.iohk.scevm.rpc

import org.json4s.JsonAST.JValue
import org.json4s.{CustomSerializer, Formats, TypeInfo}

package object serialization {
  implicit class CustomSerializerOps[T](serializer: CustomSerializer[T]) {
    def deserializePartial(formats: Formats): PartialFunction[JValue, T] =
      serializer.deserialize(formats).compose[JValue] { case v => (TypeInfo(serializer.Class, None), v) }
  }
}
