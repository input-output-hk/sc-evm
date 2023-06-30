package io.iohk.scevm.network

import cats.effect.IO
import cats.syntax.all._
import fs2.concurrent.{Signal, SignallingRef}
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.network.Generators.{peerGen, peerInfoGen}
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlock
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PeerEventSpec extends AnyWordSpec with Matchers with ScalaFutures with NormalPatience with IOSupport {

  private val peer         = peerGen.sample.get
  private val peerId       = peer.id
  private val peerInfo     = peerInfoGen.sample.get
  private val requestId    = RequestId(1L)
  private val block        = obftBlockGen.sample.get
  private val olderBlock   = block.copy(header = block.header.copy(number = BlockNumber(1)))
  private val currentBlock = block.copy(header = block.header.copy(number = BlockNumber(2)))
  private val newerBlock   = block.copy(header = block.header.copy(number = BlockNumber(3)))

  implicit private val currentBranch: Signal[IO, CurrentBranch] =
    SignallingRef[IO].of(CurrentBranch(currentBlock.header)).unsafeRunSync()

  "should filter undesired messages" in {
    val peerEvents = Seq(
      PeerEvent.MessageFromPeer(peerId, OBFT1.BlockHeaders(requestId, Nil)),
      PeerEvent.MessageFromPeer(peerId, NewBlock(olderBlock)),
      PeerEvent.MessageFromPeer(peerId, NewBlock(currentBlock)),
      PeerEvent.MessageFromPeer(peerId, NewBlock(newerBlock)),
      PeerEvent.PeerDisconnected(peerId),
      PeerEvent.PeerHandshakeSuccessful(peer, peerInfo)
    )

    val filteredPeerEvents = peerEvents.traverse(PeerEvent.filter[IO]).ioValue

    filteredPeerEvents shouldBe Seq(true, false, false, true, true, true)
  }
}
