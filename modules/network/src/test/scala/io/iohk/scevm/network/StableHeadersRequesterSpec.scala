package io.iohk.scevm.network

import cats.effect.IO
import io.iohk.scevm.network.Generators.peerWithInfoGen
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.GetStableHeaders
import io.iohk.scevm.testing.BlockGenerators.obftEmptyBodyBlockGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StableHeadersRequesterSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures
    with NormalPatience
    with IOSupport
    with TestSyncConfig {

  "StableHeaderRequester" when {

    "'new block' message received" should {
      "request stable header" in forAll(peerWithInfoGen, obftEmptyBodyBlockGen) { (peerWithInfo, block) =>
        val source = fs2.Stream(
          PeerEvent.MessageFromPeer(peerWithInfo.peer.id, OBFT1.NewBlock(block))
        )

        val result = source.through(StableHeaderRequester[IO].pipe(syncConfig)).compile.toVector.ioValue
        result.toList shouldEqual List(
          PeerAction.MessageToPeer(
            peerWithInfo.peer.id,
            GetStableHeaders(Seq(syncConfig.scoringAncestorOffset))
          )
        )
      }
    }

    "'handshake successful' message received" should {
      "request stable header" in forAll(peerWithInfoGen) { peerWithInfo =>
        val source = fs2.Stream(
          PeerEvent.PeerHandshakeSuccessful(peerWithInfo.peer, peerWithInfo.peerInfo)
        )

        val result = source.through(StableHeaderRequester[IO].pipe(syncConfig)).compile.toList.ioValue
        result shouldEqual List(
          PeerAction.MessageToPeer(
            peerWithInfo.peer.id,
            GetStableHeaders(Seq(syncConfig.scoringAncestorOffset))
          )
        )
      }
    }
  }
}
