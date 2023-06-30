package io.iohk.scevm.rpc.tapir

import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.iohk.scevm.utils.BuildInfo
import org.json4s.native.JsonMethods
import org.json4s.{DefaultFormats, Extraction}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{SttpBackend, basicRequest}
import sttp.model.Uri.UriContext
import sttp.model.{HeaderNames, MediaType, StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter

import scala.concurrent.Future

class BuildInfoEndpointSpec extends AsyncFlatSpec with Matchers with ScalatestRouteTest {
  private val backendStub: SttpBackend[Future, Any] =
    TapirStubInterpreter(SttpBackendStub.asynchronousFuture)
      .whenServerEndpoint(BuildInfoEndpoint.serverEndpoint)
      .thenRunLogic()
      .backend()

  private val uri: Uri = uri"/buildinfo"

  it should s"respond to $uri" in {
    val response = basicRequest.get(uri).send(backendStub)
    val expected = Extraction.decompose(BuildInfo.toMap)(DefaultFormats)

    response.map { r =>
      r.code shouldBe StatusCode.Ok
      r.header(HeaderNames.ContentType) shouldBe Some(MediaType.ApplicationJson.toString())
      r.body.map(res => JsonMethods.parse(res)) shouldBe Right(expected)
    }
  }
}
