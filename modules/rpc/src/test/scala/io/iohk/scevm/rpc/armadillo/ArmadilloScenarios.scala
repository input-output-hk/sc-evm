package io.iohk.scevm.rpc.armadillo

import io.circe.Json
import io.iohk.armadillo.JsonRpcEndpoint
import io.iohk.armadillo.server.stub.{ArmadilloStubInterpreter, InputCheck}
import io.iohk.scevm.rpc.serialization.ArmadilloJsonSupport
import org.json4s.{JArray, JValue}
import org.scalactic.source.Position
import org.scalatest.Assertion
import org.scalatest.exceptions.TestFailedException
import sttp.capabilities
import sttp.client3.testing.SttpBackendStub

import scala.concurrent.Future

trait ArmadilloScenarios extends ArmadilloEndpointsHelper {
  private val interpreter: ArmadilloStubInterpreter[Future, JValue, capabilities.WebSockets] =
    ArmadilloStubInterpreter(SttpBackendStub.asynchronousFuture, ArmadilloJsonSupport.jsonSupport)

  class EndpointScenarios[I, E, O](val endpoint: JsonRpcEndpoint[I, E, O]) {
    def successScenario(
        params: JValue = JArray(Nil),
        expectedInputs: I,
        serviceResponse: O,
        expectedResponse: Json
    )(implicit pos: Position): Future[Assertion] = {
      val inputCheck = InputCheck.exact[I](expectedInputs)
      val backendStub = interpreter
        .whenEndpoint(endpoint)
        .assertInputs(inputCheck)
        .thenRespond(serviceResponse)
        .backend()
      backendStub
        .send(sttpRequest(jsonRequest(endpoint.methodName.asString, params)))
        .map(assertResponse(expectedResponse))
    }

    def errorScenario(
        params: JValue = JArray(Nil),
        serverError: E,
        expectedResponse: Json
    )(implicit pos: Position): Future[Assertion] = {
      val backendStub = interpreter
        .whenEndpoint(endpoint)
        .thenRespondError(serverError)
        .backend()
      backendStub
        .send(sttpRequest(jsonRequest(endpoint.methodName.asString, params)))
        .map(assertError(expectedResponse))
    }

    def validationFailScenario(
        params: JValue = JArray(Nil),
        expectedResponse: Json
    )(implicit pos: Position): Future[Assertion] = {
      val backendStub = interpreter
        .whenEndpoint(endpoint)
        .thenThrowError(new TestFailedException("Expected validation error but endpoint logic was reached", 0))
        .backend()
      backendStub
        .send(sttpRequest(jsonRequest(endpoint.methodName.asString, params)))
        .map(assertError(expectedResponse))
    }
  }

  implicit class StringShouldPassOps(val str: String) {
    def shouldPass[I, E, O](endpoint: => EndpointScenarios[I, E, O]): Unit =
      str should (endpoint: Unit)
  }
}
