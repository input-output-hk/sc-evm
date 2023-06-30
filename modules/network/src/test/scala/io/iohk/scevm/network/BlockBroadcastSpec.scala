package io.iohk.scevm.network

import cats.effect.IO
import fs2.Stream
import io.iohk.scevm.network.Generators._
import io.iohk.scevm.network.p2p.messages.OBFT1._
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BlockBroadcastSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "BlockBroadcast" when {
    "preparePeerActions is called" should {
      "generate a PeerAction for all handshaked peers" in {
        forAll(Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen), obftBlockGen) { (generatedPeers, block) =>
          val blocks = Stream.emit(block).covary[IO]
          val peers  = Stream.emit(generatedPeers.map(_.peer).toSet).covary[IO]

          val peerActions = blocks.zip(peers).through(BlockBroadcast.toPeerActions).compile.toList.ioValue

          val expected =
            generatedPeers
              .map(peerWithInfo => PeerAction.MessageToPeer(peerWithInfo.peer.id, NewBlock(block)))
              .sortBy(_.peerId.value)
          peerActions should contain theSameElementsAs expected
        }
      }
    }
  }

}
