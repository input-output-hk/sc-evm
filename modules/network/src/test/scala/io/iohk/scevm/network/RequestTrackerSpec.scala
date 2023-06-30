package io.iohk.scevm.network

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.std.CountDownLatch
import cats.effect.testkit.TestControl
import cats.kernel.Eq
import fs2.{Pipe, Stream}
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.network.RequestTracker.{RequestDetails, RequestStates}
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

class RequestTrackerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport
    with TestSyncConfig {

  implicit private val arbPeerId: Arbitrary[PeerId]       = Arbitrary(Generators.peerIdGen)
  implicit private val arbRequestId: Arbitrary[RequestId] = Arbitrary(Gen.long.map(RequestId(_)))
  private val peerId1                                     = Generators.peerIdGen.sample.get
  private val peerId2                                     = Generators.peerIdGen.sample.get
  private val peerId3                                     = Generators.peerIdGen.sample.get
  private val req1                                        = OBFT1.GetBlockHeaders(RequestId(123), Left(BlockNumber(0)), 1, 0, false)
  private val req2                                        = req1.copy(requestId = RequestId(456))
  private val req3                                        = req2.copy(requestId = RequestId(789))

  def synchronisers()
      : (Pipe[IO, PeerAction.MessageToPeer, PeerAction.MessageToPeer], Pipe[IO, PeerEvent, PeerEvent]) = {
    val latch = CountDownLatch[IO](1).ioValue
    (_ ++ Stream.exec(latch.release), Stream.exec(latch.await) ++ _)
  }

  "RequestTracker.pipe" when {
    "requests and responses received" should {
      "only report on outstanding requests" in {
        val allIds = implicitly[Arbitrary[LazyList[(PeerId, RequestId)]]].arbitrary.sample.get.take(10)

        forAll(Gen.someOf(allIds), Gen.someOf(allIds)) { case (requestIds, responseIds) =>
          val requests = requestIds.map { case (peerId, requestId) =>
            PeerAction.MessageToPeer(peerId, OBFT1.GetBlockHeaders(requestId, Left(BlockNumber(0)), 1, 0, false))
          }
          val responses = responseIds.map { case (peerId, requestId) =>
            PeerEvent.MessageFromPeer(peerId, OBFT1.BlockHeaders(requestId, Nil))
          }
          val expectedPeerRequests    = requestIds.diff(responseIds).groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap
          val (syncAfter, syncBefore) = synchronisers()
          val actions                 = Stream.emits(requests).through(syncAfter)
          val events                  = Stream.emits(responses).through(syncBefore)

          actions
            .through2(events)(RequestTracker.pipe(syncConfig))
            .compile
            .last
            .ioValue shouldEqual Some(RequestStates(expectedPeerRequests, Nil))
        }
      }
    }

    "handle PeerDisconnected" in {
      val peerId                  = Generators.peerIdGen.sample.get
      val expectedFailures        = Seq(RequestTracker.PeerDisconnected(peerId, req1))
      val (syncAfter, syncBefore) = synchronisers()
      val actions                 = Stream.emit(PeerAction.MessageToPeer(peerId, req1)).through(syncAfter)
      val events                  = Stream.emit(PeerEvent.PeerDisconnected(peerId)).through(syncBefore)

      actions
        .through2(events)(RequestTracker.pipe(syncConfig))
        .compile
        .last
        .ioValue shouldEqual Some(RequestStates(Map.empty, expectedFailures))
    }

    "detect timeouts" in {
      val responseMessage  = OBFT1.BlockHeaders(RequestId(123), Nil)
      val peerId           = Generators.peerIdGen.sample.get
      val expectedFailures = Seq(RequestTracker.Timeout(peerId, req1))
      val delay            = syncConfig.peerResponseTimeout + 1.second
      val actions          = Stream.emit(PeerAction.MessageToPeer(peerId, req1))
      val events           = Stream.emit(PeerEvent.MessageFromPeer(peerId, responseMessage)).covary[IO].delayBy(delay + 1.second)
      val stream = actions
        .through2(events)(RequestTracker.pipe(syncConfig))
        .changes(Eq.fromUniversalEquals)
        .map(_.failures)
        .scanMonoid
        .compile
        .last

      TestControl
        .execute(stream)
        .flatMap { control =>
          control.tickAll >>
            control.advance(delay) >> // timeout hits
            control.tickAll >>
            control.advance(2.seconds) >> // response received
            control.tickAll >>
            control.results
        }
        .ioValue shouldEqual Some(Outcome.Succeeded(Some(expectedFailures)))
    }

    "should handle interleaved requests" in {
      val request1  = PeerAction.MessageToPeer(peerId1, req1)
      val request2  = PeerAction.MessageToPeer(peerId2, req1)
      val request3  = PeerAction.MessageToPeer(peerId3, req1)
      val response  = PeerEvent.MessageFromPeer(peerId1, OBFT1.BlockHeaders(RequestId(123), Nil))
      val response2 = PeerEvent.MessageFromPeer(peerId2, OBFT1.BlockHeaders(RequestId(123), Nil))
      val response3 = PeerEvent.MessageFromPeer(peerId3, OBFT1.BlockHeaders(RequestId(123), Nil))
      val delay     = syncConfig.peerResponseTimeout + 2.second

      val requestsStream = Stream.emit(request1) ++
        Stream.emit(request2).covary[IO].delayBy(3.second) ++
        Stream.emit(request3).covary[IO].delayBy(2.second)
      val responsesStream = Stream.emit(response3).covary[IO].delayBy(6.second) ++
        Stream.emit(response).covary[IO].delayBy(delay) ++
        Stream.emit(response2).covary[IO].delayBy(2.second)

      // request1 -> request2 -> request3 -> response3 -> response1 (but timeout) -> response2 (but timeout)
      val stream = requestsStream
        .through2(responsesStream)(RequestTracker.pipe(syncConfig))
        .compile
        .toList

      val result = TestControl
        .execute(stream)
        .flatMap(control => control.tickAll >> control.results)
        .ioValue

      val expected = List(
        RequestStates(Map.empty, Seq.empty),
        RequestStates(Map(peerId1 -> Set(req1.requestId)), Seq.empty),
        RequestStates(
          Map(peerId1 -> Set(req1.requestId), peerId2 -> Set(req1.requestId)),
          Seq.empty
        ),
        RequestStates(
          Map(peerId1 -> Set(req1.requestId), peerId2 -> Set(req1.requestId), peerId3 -> Set(req1.requestId)),
          Seq.empty
        ),
        // A response is received for req3
        RequestStates(Map(peerId1 -> Set(req1.requestId), peerId2 -> Set(req1.requestId)), Seq.empty),
        // Timeout for req1
        RequestStates(Map(peerId2 -> Set(req1.requestId)), Seq(RequestTracker.Timeout(peerId1, req1))),
        RequestStates(Map(peerId2 -> Set(req1.requestId)), Seq.empty),
        // Timeout for req2
        RequestStates(Map.empty, Seq(RequestTracker.Timeout(peerId2, req1))),
        RequestStates(Map.empty, Seq.empty)
      )

      result.map {
        case Outcome.Succeeded(values) => Stream.emits(values).changes.toList shouldBe expected
        case _                         => fail()
      }
    }
  }

  "State" when {
    "add is called" should {
      "add a new entry" in {
        val initialState = RequestTracker.State.empty
        val result       = initialState.add(2.second, peerId1, req1)
        val expected = RequestTracker.State(
          Map(peerId1 -> Map(req1.requestId -> RequestDetails(req1, 2.second))),
          Seq.empty
        )
        result shouldBe expected
      }
    }

    "removed is called" should {
      "remove an entry" in {
        val initialState = RequestTracker.State(
          Map(
            peerId1 -> Map(req1.requestId -> RequestDetails(req1, 2.second)),
            peerId2 -> Map(req1.requestId -> RequestDetails(req1, 3.second))
          ),
          Seq.empty
        )
        val result = initialState.remove(peerId1, req1.requestId)
        val expected = RequestTracker.State(
          Map(peerId2 -> Map(req1.requestId -> RequestDetails(req1, 3.second))),
          Seq.empty
        )
        result shouldBe expected
      }

      "be an no-op when the State is empty" in {
        val initialState = RequestTracker.State.empty
        val result       = initialState.remove(peerId1, req1.requestId)
        result shouldBe initialState
      }
    }

    "disconnect is called" should {
      "be an no-op when the State is empty" in {
        val initialState = RequestTracker.State.empty
        val result       = initialState.disconnect(peerId1)
        result shouldBe initialState
      }

      "be an no-op if the disconnected peer isn't in the state" in {
        val initialState = RequestTracker.State(
          Map(peerId1 -> Map(req1.requestId -> RequestDetails(req1, 2.second))),
          Seq.empty
        )
        val result = initialState.disconnect(peerId2)
        result shouldBe initialState
      }

      "remove requests associated to disconnected peer" in {
        val initialState = RequestTracker.State(
          Map(
            peerId1 -> Map(
              req1.requestId -> RequestDetails(req1, 2.second),
              req2.requestId -> RequestDetails(req2, 3.second)
            ),
            peerId2 -> Map(req3.requestId -> RequestDetails(req3, 1.second))
          ),
          Seq.empty
        )
        val result = initialState.disconnect(peerId1)
        val expected = RequestTracker.State(
          Map(peerId2 -> Map(req3.requestId -> RequestDetails(req3, 1.second))),
          Seq(RequestTracker.PeerDisconnected(peerId1, req1), RequestTracker.PeerDisconnected(peerId1, req2))
        )
        result shouldBe expected
      }
    }

    "checkTimeouts is called" should {
      "be an no-op when the State is empty" in {
        val initialState = RequestTracker.State.empty
        val result       = initialState.checkTimeouts(3.second)
        result shouldBe initialState
      }

      "be an no-op if there is no request in timeout" in {
        val initialState = RequestTracker.State(
          Map(peerId1 -> Map(req1.requestId -> RequestDetails(req1, 2.second))),
          Seq.empty
        )
        val result = initialState.checkTimeouts(1.second)
        result shouldBe initialState
      }

      "remove requests that have failed due to timeout" in {
        val initialState = RequestTracker.State(
          Map(
            peerId1 -> Map(
              req1.requestId -> RequestDetails(req1, 2.second),
              req2.requestId -> RequestDetails(req2, 2.second),
              req3.requestId -> RequestDetails(req3, 3.second)
            )
          ),
          Seq.empty
        )
        val result = initialState.checkTimeouts(3.second)
        val expected = RequestTracker.State(
          Map(peerId1 -> Map(req3.requestId -> RequestDetails(req3, 3.second))),
          Seq(RequestTracker.Timeout(peerId1, req1), RequestTracker.Timeout(peerId1, req2))
        )
        result shouldBe expected
      }
    }
  }
}
