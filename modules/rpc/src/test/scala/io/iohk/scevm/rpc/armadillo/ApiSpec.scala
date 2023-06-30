package io.iohk.scevm.rpc.armadillo

import io.iohk.armadillo.json.json4s.jsonRpcCodec
import io.iohk.armadillo.{JsonRpcIO, JsonRpcInput, JsonRpcOutput}
import io.iohk.scevm.rpc.serialization.JsonMethodsImplicits._
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec

class ApiSpec extends AnyWordSpec with ScalaFutures with IOSupport with NormalPatience {

  implicit private val intDocumentation: Documentation[Int] = Documentation("Meaning of life", 42)

  private val api: Api = new Api {}

  "Description" when {
    "input is called" should {
      "return a JsonRpcInput with a description and an example" in {
        val result: JsonRpcInput.Basic[Int]       = api.input[Int]("test")
        val resultAsSingle: JsonRpcIO.Single[Int] = result.asInstanceOf[JsonRpcIO.Single[Int]]

        assert(resultAsSingle.name == "test")
        assert(resultAsSingle.info.description.get == "Meaning of life")
        assert(resultAsSingle.info.examples == Set(42))
      }
    }

    "result is called" should {
      "return a JsonRpcOutput with a description and an example (default language)" in {
        val result: JsonRpcOutput.Basic[Int]      = api.output[Int]("test")
        val resultAsSingle: JsonRpcIO.Single[Int] = result.asInstanceOf[JsonRpcIO.Single[Int]]

        assert(resultAsSingle.name == "test")
        assert(resultAsSingle.info.description.get == "Meaning of life")
        assert(resultAsSingle.info.examples == Set(42))
      }
    }
  }
}
