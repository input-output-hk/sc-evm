package io.iohk.scevm.rpc.armadillo.openrpc

import cats.effect.IO
import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.armadillo._
import io.iohk.armadillo.json.json4s._
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.utils.BuildInfo
import org.json4s.JsonAST.{JArray, JValue}

class OpenRpcDiscoveryServiceSpec extends ArmadilloScenarios {

  private val endpoint: JsonRpcEndpoint[String, Unit, Option[String]] = jsonRpcEndpoint(m"test")
    .in(param[String]("name"))
    .out[Option[String]]("message")

  private val openRpcDiscoveryServiceEndpoint = OpenRpcDiscoveryService.serverEndpoint[IO](
    List(endpoint)
  )

  private val binaryVersion: String = BuildInfo.version

  private val expectedResponseCirce: Json =
    json"""
      {
        "openrpc": "1.2.1",
        "info": {
          "version": $binaryVersion,
          "title": "SC EVM RPC specification"
        },
        "methods": [
          {
            "name": "test",
            "params": [
              {
                "name": "name",
                "required": true,
                "schema": {
                  "type": "string"
                }
              }
            ],
            "result": {
              "name": "message",
              "schema": {
                "oneOf": [
                  {
                    "$$ref": "#/components/schemas/Null"
                  },
                  {
                    "type": "string"
                  }
                ]
              }
            }
          }
        ],
        "components": {
          "schemas": {
            "Null": {
              "title": "Null",
              "type": "object",
              "description": "null"
            }
          }
        }
      }
    """

  val expectedResponseJson4s: JValue = circeToJson4s(expectedResponseCirce)

  "serviceDiscovery" shouldPass new EndpointScenarios(openRpcDiscoveryServiceEndpoint.endpoint) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = expectedResponseJson4s,
      expectedResponse = expectedResponseCirce
    )
  }

}
