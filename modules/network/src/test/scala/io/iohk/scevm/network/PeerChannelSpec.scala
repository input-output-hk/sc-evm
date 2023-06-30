package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.IO
import fs2.concurrent.Topic
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.network.Generators.peerIdGen
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.testing.BlockGenerators.{obftBlockChainGen, obftEmptyBodyBlockGen}
import io.iohk.scevm.testing.{IOSupport, NormalPatience, fixtures}
import org.scalacheck.Shrink
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

class PeerChannelSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  "PeerChannel.askPeers" when {
    "requested a message from multiple peers" should {
      "return the response with the matching request id and ignore the rest" in {
        forAll(obftBlockChainGen(50, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen), peerIdGen) {
          (blocks, peerId) =>
            val test = for {
              peerEventsTopic  <- Topic[IO, PeerEvent]
              peerActionsTopic <- Topic[IO, PeerAction]

              requestId = RequestId(42)
              request   = OBFT1.GetFullBlocks(requestId, blocks.last.hash, blocks.size)

              ignoredMessage1 = PeerAction.MessageToPeer(
                                  PeerId("random-peer-1"),
                                  OBFT1.GetFullBlocks(RequestId(57), BlockHash(ByteString.empty), 0)
                                )
              ignoredMessage2 = PeerAction.MessageToPeer(
                                  PeerId("random-peer-2"),
                                  OBFT1.GetFullBlocks(RequestId(557), BlockHash(ByteString.empty), 1)
                                )

              channel = TopicBasedPeerChannel(
                          peerEventsTopic,
                          peerActionsTopic,
                          1.second,
                          1
                        )

              fullBlocks <- FullBlockResponseMock.runWithResponseMock(
                              channel.askPeers[OBFT1.FullBlocks](NonEmptyList.one(peerId), request),
                              peerEventsTopic,
                              peerActionsTopic,
                              blocks,
                              mockResponses => fs2.Stream(ignoredMessage1, ignoredMessage2) ++ mockResponses
                            )
            } yield fullBlocks match {
              case Left(error) => fail(error.toString)
              case Right(response) =>
                assert(response.requestId == requestId)
                // GetFullBlocks is returning blocks from requested head back to 'count' ancestors
                assert(response.blocks == blocks.reverse)
            }
            test.ioValue
        }
      }

      "if the first requested peer times out -- switch to the next peer in the list" in {
        forAll(obftBlockChainGen(50, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen), peerIdGen, peerIdGen) {
          (blocks, timeoutPeer, normalPeer) =>
            val test = for {
              peerEventsTopic  <- Topic[IO, PeerEvent]
              peerActionsTopic <- Topic[IO, PeerAction]

              requestId = RequestId(42)
              request   = OBFT1.GetFullBlocks(requestId, blocks.last.hash, blocks.size)

              channel = TopicBasedPeerChannel(
                          peerEventsTopic,
                          peerActionsTopic,
                          100.millis,
                          1
                        )

              fullBlocks <- FullBlockResponseMock.runWithResponseMock(
                              channel.askPeers[OBFT1.FullBlocks](
                                NonEmptyList.of(timeoutPeer, normalPeer),
                                request
                              ), // timeoutPeer is the first peer to be asked
                              peerEventsTopic,
                              peerActionsTopic,
                              blocks,
                              mockResponses =>
                                mockResponses.filter {
                                  case PeerAction.MessageToPeer(pId, _) =>
                                    pId == normalPeer
                                  case _ => false
                                } // response from the timeoutPeer is ignored to emulate timeout
                            )
            } yield fullBlocks match {
              case Left(error) => fail(error.toString)
              case Right(response) =>
                assert(response.requestId == requestId)
                // GetFullBlocks is returning blocks from requested head back to 'count' ancestors
                assert(response.blocks == blocks.reverse)
            }
            test.ioValue
        }
      }

      "fail with MultiplePeersAskFailure exception otherwise" in {
        val test = for {
          peerEventsTopic  <- Topic[IO, PeerEvent] // remains empty
          peerActionsTopic <- Topic[IO, PeerAction]

          requestId = RequestId(42)
          request   = OBFT1.GetFullBlocks(requestId, BlockHash(ByteString("0x0")), 1)

          channel = TopicBasedPeerChannel(
                      peerEventsTopic,
                      peerActionsTopic,
                      0.second,
                      1
                    )

          fullBlocks <-
            channel
              .askPeers[OBFT1.FullBlocks](NonEmptyList.of(PeerId("peerId"), PeerId("peer2"), PeerId("peer3")), request)
        } yield fullBlocks match {
          case Left(error)     => assert(error == PeerChannel.MultiplePeersAskFailure.AllPeersTimedOut)
          case Right(response) => fail("Expected timeout error, got response: " + response)
        }

        test.ioValue
      }

      "if the first requested peer returns empty response -- switch to the next peer in the list" in {
        forAll(obftBlockChainGen(50, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen), peerIdGen, peerIdGen) {
          (blocks, forkedPeer, normalPeer) =>
            val test = for {
              peerEventsTopic <- Topic[IO, PeerEvent].map(_.imap {
                                   case event @ PeerEvent.MessageFromPeer(peerId, message: OBFT1.FullBlocks)
                                       if peerId == forkedPeer =>
                                     event.copy(message = message.copy(blocks = List.empty))
                                   case other => other
                                 }(identity))
              peerActionsTopic <- Topic[IO, PeerAction]

              requestId = RequestId(42)
              request   = OBFT1.GetFullBlocks(requestId, blocks.last.hash, blocks.size)

              channel = TopicBasedPeerChannel(
                          peerEventsTopic,
                          peerActionsTopic,
                          100.millis,
                          1
                        )

              fullBlocks <- FullBlockResponseMock.runWithResponseMock(
                              channel.askPeers[OBFT1.FullBlocks](
                                NonEmptyList.of(forkedPeer, normalPeer),
                                request
                              ), // forkedPeer is the first peer to be asked
                              peerEventsTopic,
                              peerActionsTopic,
                              blocks
                            )
            } yield fullBlocks match {
              case Left(error) => fail(error.toString)
              case Right(response) =>
                assert(response.requestId == requestId)
                // GetFullBlocks is returning blocks from requested head back to 'count' ancestors
                assert(response.blocks == blocks.reverse)
            }
            test.ioValue
        }
      }

      "if all peers requested return empty response -- return empty response" in {
        val test = for {
          peerEventsTopic  <- Topic[IO, PeerEvent]
          peerActionsTopic <- Topic[IO, PeerAction]

          requestId = RequestId(42)
          request   = OBFT1.GetFullBlocks(requestId, BlockHash(ByteString("0x0")), 1)

          channel = TopicBasedPeerChannel(
                      peerEventsTopic,
                      peerActionsTopic,
                      100.millis,
                      1
                    )

          fullBlocks <- FullBlockResponseMock.runWithResponseMock(
                          channel.askPeers[OBFT1.FullBlocks](
                            NonEmptyList.of(PeerId("peerId"), PeerId("peer2"), PeerId("peer3")),
                            request
                          ),
                          peerEventsTopic,
                          peerActionsTopic,
                          List.empty
                        )
        } yield fullBlocks match {
          case Left(error) => fail("Expected empty response, but got error instead: " + error)
          case Right(response) =>
            assert(response.requestId == requestId)
            assert(response.blocks.isEmpty)
        }

        test.ioValue
      }
    }
  }
}
