//package io.iohk.scevm.network

// TODO needs further updating - EphemBlockchainTestSetup needs to be ported
// import akka.actor.{ActorRef, ActorSystem, Props}
// import akka.testkit.TestProbe
// import com.miguno.akka.testing.VirtualTime
// import io.iohk.scevm.network.KnownNodesManager.KnownNodesManagerConfig
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatest.matchers.should.Matchers

// import java.net.URI
// import scala.concurrent.duration._

// class KnownNodesManagerSpec extends AnyFlatSpec with Matchers {

//   "KnownNodesManager" should "keep a list of nodes and persist changes" in new TestSetup {
//     knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
//     client.expectMsg(KnownNodesManager.KnownNodes(Set.empty))

//     knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(1)), client.ref)
//     knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(2)), client.ref)
//     knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
//     client.expectMsg(KnownNodesManager.KnownNodes(Set(uri(1), uri(2))))
//     storagesInstance.storages.knownNodesStorage.getKnownNodes() shouldBe Set.empty

//     time.advance(config.persistInterval + 10.seconds)

//     knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
//     client.expectMsg(KnownNodesManager.KnownNodes(Set(uri(1), uri(2))))
//     storagesInstance.storages.knownNodesStorage.getKnownNodes() shouldBe Set(uri(1), uri(2))

//     knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(3)), client.ref)
//     knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(4)), client.ref)
//     knownNodesManager.tell(KnownNodesManager.RemoveKnownNode(uri(1)), client.ref)
//     knownNodesManager.tell(KnownNodesManager.RemoveKnownNode(uri(4)), client.ref)

//     time.advance(config.persistInterval + 10.seconds)

//     knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
//     client.expectMsg(KnownNodesManager.KnownNodes(Set(uri(2), uri(3))))

//     storagesInstance.storages.knownNodesStorage.getKnownNodes() shouldBe Set(uri(2), uri(3))
//   }

//   it should "respect max nodes limit" in new TestSetup {
//     knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
//     client.expectMsg(KnownNodesManager.KnownNodes(Set.empty))

//     (1 to 10).foreach { n =>
//       knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(n)), client.ref)
//     }
//     time.advance(config.persistInterval + 1.seconds)

//     knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
//     client.expectMsgClass(classOf[KnownNodesManager.KnownNodes])

//     storagesInstance.storages.knownNodesStorage.getKnownNodes().size shouldBe 5
//   }

//   trait TestSetup extends EphemBlockchainTestSetup {
//     implicit override lazy val system: ActorSystem = ActorSystem("KnownNodesManagerSpec_System")

//     val time = new VirtualTime
//     val config: KnownNodesManagerConfig = KnownNodesManagerConfig(persistInterval = 5.seconds, maxPersistedNodes = 5)

//     val client: TestProbe = TestProbe()

//     def uri(n: Int): URI = new URI(s"enode://test$n@test$n.com:9000")

//     val knownNodesManager: ActorRef = system.actorOf(
//       Props(new KnownNodesManager(config, storagesInstance.storages.knownNodesStorage, Some(time.scheduler)))
//     )
//   }

// }
