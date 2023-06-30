package io.iohk.scevm.network

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{BlocksDistance, Slot}
import io.iohk.scevm.network.Generators.peerWithInfoGen
import io.iohk.scevm.network.domain.{ChainDensity, ChainTooShort, StableHeaderScore}
import io.iohk.scevm.network.forkid.ForkId
import io.iohk.scevm.network.metrics.NoOpNetworkMetrics
import io.iohk.scevm.network.p2p.messages.OBFT1.{StableHeaders, Status}
import io.iohk.scevm.testing.BlockGenerators.{blockHashGen, obftBlockHeaderGen}
import io.iohk.scevm.testing.fixtures.ValidBlock
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration.DurationInt

class PeerWithInfoSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "PeerWithInfo" when {

    "updatePeerBranchScore" should {
      "update only to better stable" in forAll(peerWithInfoGen, obftBlockHeaderGen) { (peerWithInfo, header) =>
        val res = peerWithInfo.updatePeerBranchScore(StableHeaderScore(header, 0.0))

        res.peerInfo.peerBranchScore match {
          case None                               => fail("StableHeader should have been set")
          case Some(StableHeaderScore(stable, _)) => assert(stable.number >= header.number)
          case Some(ChainTooShort(stable))        => assert(stable.number >= header.number)
          case Some(other) =>
            fail(s"Unexpected result=$other, expecting StableHeaderScore or ChainTooShort with a highest stable number")
        }
      }
    }

    "aggregateState" should {
      "handle connection" in forAll(peerWithInfoGen) { peerWithInfo =>
        // Given
        val source = fs2.Stream.emits(
          List(
            PeerEvent.PeerHandshakeSuccessful(peerWithInfo.peer, peerWithInfo.peerInfo)
          )
        )

        // When
        val res = source
          .through(PeerWithInfo.aggregateState[IO](NoOpNetworkMetrics[IO]()))
          .compile
          .toVector
          .ioValue

        // Then
        res.toList shouldEqual List(Map.empty, Map(peerWithInfo.peer.id -> peerWithInfo))
      }

      "handle disconnection" in forAll(peerWithInfoGen) { peerWithInfo =>
        // Given
        val source = fs2.Stream.emits(
          List(
            PeerEvent.PeerHandshakeSuccessful(peerWithInfo.peer, peerWithInfo.peerInfo),
            PeerEvent.PeerDisconnected(peerWithInfo.peer.id)
          )
        )

        // When
        val res = source
          .through(PeerWithInfo.aggregateState[IO](NoOpNetworkMetrics[IO]()))
          .compile
          .toVector
          .ioValue

        // Then
        res.toList shouldEqual List(Map.empty, Map(peerWithInfo.peer.id -> peerWithInfo), Map.empty)
      }

      "update stable on new StableHeader for the proper peer" in forAll(
        peerWithInfoGen,
        peerWithInfoGen,
        obftBlockHeaderGen,
        obftBlockHeaderGen
      ) { (peerWithInfo1, peerWithInfo2, header1, header2) =>
        val k       = 10
        val genesis = ValidBlock.header
        // stable and ancestor should have at least k blocks. Changing slotNumber to have a valid density
        val stable1 =
          header1.copy(
            number = genesis.number + BlocksDistance(10 * k),
            slotNumber = Slot(genesis.slotNumber.number + BigInt(20 * k))
          )
        val ancestor1 =
          header1.copy(
            number = genesis.number + BlocksDistance(5 * k),
            slotNumber = Slot(genesis.slotNumber.number + BigInt(10 * k))
          )
        val stable2 =
          header2.copy(
            number = genesis.number + BlocksDistance(10 * k),
            slotNumber = Slot(genesis.slotNumber.number + BigInt(20 * k))
          )
        // Given two successful handshakes and two StableHeader update messages
        val source = fs2.Stream.emits(
          List(
            PeerEvent.PeerHandshakeSuccessful(peerWithInfo1.peer, peerWithInfo1.peerInfo),
            PeerEvent.PeerHandshakeSuccessful(peerWithInfo2.peer, peerWithInfo2.peerInfo),
            PeerEvent.MessageFromPeer(peerWithInfo1.peer.id, StableHeaders(RequestId(0), stable1, List(ancestor1))),
            PeerEvent.MessageFromPeer(peerWithInfo2.peer.id, StableHeaders(RequestId(0), stable2, List.empty))
          )
        )

        // When they are fed to the aggregateState
        val res = source
          .through(PeerWithInfo.aggregateState[IO](NoOpNetworkMetrics[IO]()))
          .compile
          .toVector
          .ioValue

        // Then the stable header is updated accordingly for the proper peer
        val updatedValue1 =
          peerWithInfo1.updatePeerBranchScore(StableHeaderScore(stable1, ChainDensity.density(stable1, ancestor1)))
        val updatedValue2 =
          peerWithInfo2.updatePeerBranchScore(ChainTooShort(stable2))

        res shouldEqual Vector(
          Map.empty,
          Map(peerWithInfo1.peer.id -> peerWithInfo1),
          Map(peerWithInfo1.peer.id -> peerWithInfo1, peerWithInfo2.peer.id -> peerWithInfo2),
          Map(peerWithInfo1.peer.id -> updatedValue1, peerWithInfo2.peer.id -> peerWithInfo2),
          Map(peerWithInfo1.peer.id -> updatedValue1, peerWithInfo2.peer.id -> updatedValue2)
        )
      }

      "not produce on other messages" in forAll(peerWithInfoGen, blockHashGen) { (peerWithInfo, hash) =>
        // Given
        val source = fs2.Stream.emits(
          List(
            PeerEvent.PeerHandshakeSuccessful(peerWithInfo.peer, peerWithInfo.peerInfo),
            PeerEvent.MessageFromPeer(
              peerWithInfo.peer.id,
              Status(1, 1, ChainId(2), hash, hash, ForkId(1, None), 15, 5.seconds, ByteString(1))
            )
          )
        )

        // When
        val res = source
          .through(PeerWithInfo.aggregateState[IO](NoOpNetworkMetrics[IO]()))
          .compile
          .toVector
          .ioValue

        // Then
        res.toList shouldEqual List(
          Map.empty,
          Map(peerWithInfo.peer.id -> peerWithInfo)
        )
      }
    }
  }
}
