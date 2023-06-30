package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.list._
import fs2.Stream
import fs2.concurrent.Signal
import io.iohk.scevm.domain.{BlockNumber, ObftBlock, ObftBody}
import io.iohk.scevm.network.Generators.peerGen
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.testing.{IOSupport, NormalPatience, fixtures}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PeerActionSpec extends AnyWordSpec with Matchers with ScalaFutures with NormalPatience with IOSupport {

  val genesis = fixtures.GenesisBlock.block
  val block1: ObftBlock =
    ObftBlock(genesis.header.copy(parentHash = genesis.hash, number = BlockNumber(1)), ObftBody.empty)
  val block2: ObftBlock =
    ObftBlock(block1.header.copy(parentHash = block1.hash, number = BlockNumber(2)), ObftBody.empty)

  val peerGenesis: PeerWithInfo        = PeerWithInfo(peerGen.sample.get, PeerInfo(None))
  val peerBetter: PeerWithInfo         = PeerWithInfo(peerGen.sample.get, PeerInfo(None))
  val peerBest: PeerWithInfo           = PeerWithInfo(peerGen.sample.get, PeerInfo(None))
  val peers: Map[PeerId, PeerWithInfo] = Seq(peerGenesis, peerBetter, peerBest).map(pwi => pwi.peer.id -> pwi).toMap

  val requestMessage: OBFT1.GetBlockBodies = OBFT1.GetBlockBodies(Nil)

  "PeerAction.messagesToConnectedPeers" when {
    "receives MessageToPeer" should {
      "should always send the message to the specified peer" in {
        val mtp = PeerAction.MessageToPeer(peerGenesis.id, requestMessage)
        Stream(mtp)
          .through(PeerAction.messagesToConnectedPeers[IO](Signal.constant(peers)))
          .compile
          .toList
          .ioValue shouldEqual Seq(mtp)
      }
    }

    "receives MessageToPeers" should {
      "use all specified peers, if connected" in {
        val specifiedPeerIds = peers.removed(peerGenesis.id).keySet.toList.toNel.get
        Stream(PeerAction.MessageToPeers(specifiedPeerIds, requestMessage))
          .through(PeerAction.messagesToConnectedPeers[IO](Signal.constant(peers)))
          .compile
          .toList
          .ioValue shouldEqual specifiedPeerIds.map(PeerAction.MessageToPeer(_, requestMessage)).toList
      }

      "emit nothing when there's no connected peers" in {
        val specifiedPeerIds = peers.keySet.toList.toNel.get
        Stream(PeerAction.MessageToPeers(specifiedPeerIds, requestMessage))
          .through(PeerAction.messagesToConnectedPeers[IO](Signal.constant(Map.empty)))
          .compile
          .toList
          .ioValue shouldEqual Nil
      }

      "selects specified peer over best connected peer" in {
        Stream(PeerAction.MessageToPeers(NonEmptyList.of(peerGenesis.id), requestMessage))
          .through(PeerAction.messagesToConnectedPeers[IO](Signal.constant(peers)))
          .compile
          .toList
          .ioValue shouldEqual Seq(PeerAction.MessageToPeer(peerGenesis.id, requestMessage))
      }
    }

    "receives MessageToAll" should {
      "selects all connected peers" in {
        Stream(PeerAction.MessageToAll(requestMessage))
          .through(PeerAction.messagesToConnectedPeers[IO](Signal.constant(peers)))
          .compile
          .toList
          .ioValue shouldEqual peers.keySet.toSeq.map(PeerAction.MessageToPeer(_, requestMessage))
      }
    }
  }
}
