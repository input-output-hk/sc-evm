package io.iohk.scevm.network.stats

import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.concurrent.Topic
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlockHashes
import io.iohk.scevm.network.{PeerEvent, PeerEventBus, PeerId}
import io.iohk.scevm.testing.{ActorsTesting, MockClock}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class PeerStatisticsSpec
    extends TestKit(ActorSystem("PeerStatisticsSpec_System"))
    with AnyFlatSpecLike
    with Matchers
    with Eventually
    with BeforeAndAfterAll {

  import PeerStatisticsActor._

  val TICK: Long = 50
  val mockClock: MockClock = new MockClock(0L) {
    override def millis(): Long = {
      windByMillis(TICK)
      super.millis()
    }
  }

  behavior.of("PeerStatisticsActor")

  it should "initially return default stats for unknown peers" in new Fixture {
    val peerId = PeerId("Alice")
    peerStatistics ! GetStatsForPeer(1.minute, peerId)
    sender.expectMsg(StatsForPeer(peerId, PeerStat.empty))
  }

  it should "initially return default stats when there are no peers" in new Fixture {
    peerStatistics ! GetStatsForAll(1.minute)
    sender.expectMsg(StatsForAll(Map.empty))
  }

  it should "count received messages" in new Fixture {
    val alice = PeerId("Alice")
    val bob   = PeerId("Bob")

    (for {
      _ <- ActorsTesting.awaitActorReady(peerStatistics)
      _ <- peerEventBus.publish(PeerEvent.MessageFromPeer(alice, NewBlockHashes(Seq.empty)))
      _ <- peerEventBus.publish(PeerEvent.MessageFromPeer(bob, NewBlockHashes(Seq.empty)))
      _ <- peerEventBus.publish(PeerEvent.MessageFromPeer(alice, NewBlockHashes(Seq.empty)))
    } yield ()).unsafeRunSync()

    val stats = eventually {
      peerStatistics ! GetStatsForAll(1.minute)

      val stats = sender.expectMsgType[StatsForAll]
      stats.stats should not be empty
      stats.stats(alice).responsesReceived shouldBe 2
      stats
    }

    val statA = stats.stats(alice)
    val difference = for {
      first <- statA.firstSeenTimeMillis
      last  <- statA.lastSeenTimeMillis
    } yield last - first
    assert(difference.exists(_ >= TICK))

    val statB = stats.stats(bob)
    statB.responsesReceived shouldBe 1
    statB.lastSeenTimeMillis shouldBe statB.firstSeenTimeMillis
  }

  trait Fixture {
    val sender: TestProbe            = TestProbe()
    implicit val senderRef: ActorRef = sender.ref
    implicit val timeout: Timeout    = Timeout(1.second)

    val (peerEventBus, peerStatistics) =
      (for {
        peerEventBus <- Topic[IO, PeerEvent].map(PeerEventBus(_, 1))
        actor        <- IO(buildActor(peerEventBus)).flatTap(ActorsTesting.awaitActorReady)
      } yield (peerEventBus, actor)).unsafeRunSync()

  }

  private def buildActor(peerEventBus: PeerEventBus[IO]) =
    system.actorOf(
      PeerStatisticsActor.props(peerEventBus, slotDuration = 1.minute, slotCount = 30)(mockClock)
    )

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
}
