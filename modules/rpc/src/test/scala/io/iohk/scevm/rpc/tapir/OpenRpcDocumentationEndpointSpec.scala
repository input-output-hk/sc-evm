package io.iohk.scevm.rpc.tapir

import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{SttpBackend, basicRequest}
import sttp.model.Uri.UriContext
import sttp.model.{HeaderNames, StatusCode, Uri}
import sttp.tapir.server.ServerEndpoint.Full
import sttp.tapir.server.stub.TapirStubInterpreter

import scala.concurrent.Future

class OpenRpcDocumentationEndpointSpec extends AsyncFlatSpec with Matchers with ScalatestRouteTest {
  private val renderingEngineUrl: String  = "https://playground.open-rpc.org/?schemaUrl="
  private val schemaUrl: String           = "http://localhost:8546"
  private val expectedRedirectUrl: String = s"$renderingEngineUrl$schemaUrl"

  private val serverEndpoint: Full[Unit, Unit, Unit, Unit, String, Any, Future] =
    new OpenRpcDocumentationEndpoint[Future](renderingEngineUrl, schemaUrl).serverEndpoint

  private val backendStub: SttpBackend[Future, Any] =
    TapirStubInterpreter(SttpBackendStub.asynchronousFuture)
      .whenServerEndpoint(serverEndpoint)
      .thenRunLogic()
      .backend()

  private val uri: Uri = uri"/openrpc/docs"

  it should s"respond to $uri" in {
    val response = basicRequest
      .get(uri)
      .send(backendStub)

    response.map { r =>
      r.code shouldBe StatusCode.Found
      r.header(HeaderNames.Location) shouldBe Some(expectedRedirectUrl)
      r.body shouldBe Left("")
    }
  }
}
