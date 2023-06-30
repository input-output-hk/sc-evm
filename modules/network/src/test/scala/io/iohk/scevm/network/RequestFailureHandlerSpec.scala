package io.iohk.scevm.network

import cats.effect.IO
import fs2.Stream
import fs2.concurrent.Signal
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.network.RequestTracker.{Failure, PeerDisconnected, RequestStates, Timeout}
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RequestFailureHandlerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with NormalPatience
    with IOSupport
    with BeforeAndAfterAll {

  "RequestTracker.regeneratePeerAction" when {
    "request failures are received" should {
      "generate PeerAction associated to the less busy peer" in new Fixture {
        val failedRequests = Stream.emits(
          Seq(
            RequestStates(
              Map(
                peerId1 -> Set(RequestId(1), RequestId(2), RequestId(3), RequestId(4)),
                peerId2 -> Set(RequestId(6))
              ),
              Seq[Failure](
                Timeout(peerId3, requestMessage1),
                PeerDisconnected(peerId3, requestMessage2),
                PeerDisconnected(peerId3, requestMessage3)
              )
            )
          )
        )

        // Only Peer1 and Peer2 are available (Peer3 is disconnected), but Peer2 has a better score
        val expected = List(
          PeerAction.MessageToPeer(peerId2, requestMessage1),
          PeerAction.MessageToPeer(peerId2, requestMessage2),
          PeerAction.MessageToPeer(peerId2, requestMessage3)
        )

        val result = executeRegeneratePeerAction(failedRequests, myPeerId, peerIdsSignal)

        result shouldEqual expected
      }

      "generate PeerAction associated to the less busy peer (same score)" in new Fixture {
        val failedRequests = Stream.emits(
          Seq(
            RequestStates(
              Map(peerId1 -> Set(RequestId(1)), peerId2 -> Set(RequestId(2))),
              Seq[Failure](
                Timeout(peerId3, requestMessage1),
                PeerDisconnected(peerId3, requestMessage2),
                PeerDisconnected(peerId3, requestMessage3)
              )
            )
          )
        )

        val result = executeRegeneratePeerAction(failedRequests, myPeerId, peerIdsSignal)

        // When Peer1 and Peer2 have the same score,
        // Peer1 is chosen because it's the closest peer according to Kademlia distance
        val expected = List(
          PeerAction.MessageToPeer(peerId1, requestMessage1),
          PeerAction.MessageToPeer(peerId2, requestMessage2),
          PeerAction.MessageToPeer(peerId1, requestMessage3)
        )

        result shouldBe expected
      }

      "handle case where a failed request has already been sent to every peer" in new Fixture {
        val failedRequests = Stream.emits(
          Seq(
            RequestStates(Map.empty, Seq[Failure](Timeout(peerId2, requestMessage1))),
            RequestStates(Map.empty, Seq[Failure](Timeout(peerId3, requestMessage1))),
            RequestStates(Map.empty, Seq[Failure](Timeout(peerId1, requestMessage1)))
          )
        )

        // 1. Peer1 and Peer3 are available and not busy, but Peer1 is closest according to Kademlia distance
        // 2. Only Peer1 is considered as available (Peer2 has already tried to process requestMessage1)
        // 3. There are no peers available
        val expected = List(
          PeerAction.MessageToPeer(peerId1, requestMessage1),
          PeerAction.MessageToPeer(peerId1, requestMessage1)
        )

        val result = executeRegeneratePeerAction(failedRequests, myPeerId, peerIdsSignal)

        result shouldEqual expected
      }

      "generate nothing if there are no failed requests" in new Fixture {
        val failedRequests = Stream.emits(
          Seq(RequestStates(Map(peerId1 -> Set(RequestId(1)), peerId2 -> Set(RequestId(2))), Seq.empty))
        )

        val result = executeRegeneratePeerAction(failedRequests, myPeerId, peerIdsSignal)

        val expected = List.empty

        result shouldBe expected
      }

      "generate nothing if there are no peer" in new Fixture {
        val failedRequests = Stream.emits(Seq(RequestStates(Map.empty, Seq(Timeout(peerId1, requestMessage1)))))

        val result = executeRegeneratePeerAction(failedRequests, myPeerId, emptyPeerIdsSignal)

        val expected = List.empty

        result shouldBe expected
      }
    }
  }

  trait Fixture {
    protected val myPeerId: PeerId     = PeerId("peer0")
    protected val peerId1: PeerId      = PeerId("peer1")
    protected val peerId2: PeerId      = PeerId("peer2")
    protected val peerId3: PeerId      = PeerId("peer3")
    protected val peerIds: Set[PeerId] = Set(peerId1, peerId2, peerId3)

    protected val requestMessage1: OBFT1.GetBlockHeaders =
      OBFT1.GetBlockHeaders(RequestId(123), Left(BlockNumber(0)), 1, 0, false)
    protected val requestMessage2: OBFT1.GetBlockHeaders =
      OBFT1.GetBlockHeaders(RequestId(456), Left(BlockNumber(0)), 1, 0, false)
    protected val requestMessage3: OBFT1.GetBlockHeaders =
      OBFT1.GetBlockHeaders(RequestId(789), Left(BlockNumber(0)), 1, 0, false)

    protected val peerIdsSignal: Signal[IO, Set[PeerId]] =
      Signal.constant[IO, Set[PeerId]](Set(peerId1, peerId2, peerId3))
    protected val emptyPeerIdsSignal: Signal[IO, Set[PeerId]] = Signal.constant[IO, Set[PeerId]](Set.empty)
  }

  private def executeRegeneratePeerAction(
      failedRequests: Stream[IO, RequestStates],
      myPeerId: PeerId,
      peerIdsSignal: Signal[IO, Set[PeerId]]
  ): List[PeerAction] =
    failedRequests
      .through(RequestFailureHandler.regeneratePeerAction(myPeerId, peerIdsSignal))
      .compile
      .toList
      .ioValue
}
