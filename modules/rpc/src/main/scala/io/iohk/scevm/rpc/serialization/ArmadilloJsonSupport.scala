package io.iohk.scevm.rpc.serialization

import io.iohk.armadillo.json.json4s
import io.iohk.armadillo.json.json4s.Json4sSupport
import org.json4s.{DefaultFormats, Formats, Serialization, native}

object ArmadilloJsonSupport {
  implicit private val serialization: Serialization = native.Serialization
  implicit private val formats: Formats             = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  val jsonSupport: Json4sSupport =
    json4s.Json4sSupport(
      org.json4s.native.parseJson[String](_),
      j => org.json4s.native.compactJson(org.json4s.native.renderJValue(j))
    )
}
