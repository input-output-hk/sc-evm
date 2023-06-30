package io.iohk.scevm.rpc.tapir

import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.iohk.scevm.utils.{NodeState, NodeStatus, NodeStatusProvider, ServerStatus}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{SttpBackend, basicRequest}
import sttp.model.Uri.UriContext
import sttp.model.{HeaderNames, MediaType, StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter

import scala.concurrent.Future

class HealthCheckServiceSpec extends AsyncFlatSpec with Matchers with ScalatestRouteTest {
  private val nodeStatusProviderStub: NodeStatusProvider[Future] = new NodeStatusProvider[Future] {
    override def getNodeStatus: Future[NodeStatus]                         = ???
    override def getNodeState: Future[NodeState]                           = Future(NodeState.Running)
    override def setNodeState(nodeState: NodeState): Future[Unit]          = ???
    override def setServerStatus(serverStatus: ServerStatus): Future[Unit] = ???
  }

  private val backendStub: SttpBackend[Future, Any] =
    TapirStubInterpreter(SttpBackendStub.asynchronousFuture)
      .whenServerEndpoint(HealthCheckService.serverEndpoint[Future](nodeStatusProviderStub))
      .thenRunLogic()
      .backend()

  private val uri: Uri = uri"/${HealthCheckService.healthCheck}"

  it should s"respond to $uri with 200 OK" in {
    val response = basicRequest
      .get(uri)
      .send(backendStub)

    response.map { r =>
      r.code shouldBe StatusCode.Ok
      r.header(HeaderNames.ContentType) shouldBe Some(MediaType.ApplicationJson.toString())
      r.body shouldBe Right("""{"state":"Running","checks":[]}""")
    }
  }

}
