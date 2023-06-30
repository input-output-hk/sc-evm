package io.iohk.scevm.rpc

import io.circe.Json
import org.json4s.JsonAST.{JArray, JBool, JInt, JNull, JObject, JString, JValue}

trait JsonHelper {
  def circeToJson4s(circeJson: Json): JValue =
    circeJson.fold[JValue](
      JNull,
      jsonBoolean => JBool(jsonBoolean),
      jsonNumber => JInt(jsonNumber.toBigInt.get),
      jsonString => JString(jsonString),
      jsonArray => JArray(jsonArray.map(subCirceJson => circeToJson4s(subCirceJson)).toList),
      jsonObject => JObject(jsonObject.toList.map { case (key, subCirceJson) => (key, circeToJson4s(subCirceJson)) })
    )
}
