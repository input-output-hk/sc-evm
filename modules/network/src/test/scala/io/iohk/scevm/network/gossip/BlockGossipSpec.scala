package io.iohk.scevm.network.gossip

import cats.effect.IO
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.consensus.validators.HeaderValidator.HeaderError
import io.iohk.scevm.domain.{ObftBlock, ObftHeader}
import io.iohk.scevm.network.Generators.peerWithInfoGen
import io.iohk.scevm.network.p2p.Message
import io.iohk.scevm.network.p2p.messages.OBFT1.NewBlock
import io.iohk.scevm.network.{PeerAction, PeerEvent, PeerId, PeerWithInfo}
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.Generators
import org.scalacheck.Gen
import org.scalacheck.Shrink.shrinkAny
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class BlockGossipSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  implicit val loggerIO: Logger[IO] = Slf4jLogger.getLogger[IO]
  val stabilityParameter            = 5
  val gossipCacheFactor             = 2.0
  val genBlocksLen: Int             = (stabilityParameter * gossipCacheFactor).toInt + 1
  import cats.effect.unsafe.implicits.global

  "BlockGossip" when {
    "receiving a new Block" should {

      "gossip all valid non-gossiped blocks to all peers but the source" in {
        forAll(
          Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen),
          Gen.listOfN[ObftBlock](genBlocksLen, obftBlockGen).suchThat(_.nonEmpty)
        ) { case (peersWithInfo, blocks) =>
          val sourcePeerIndex = Generators.intGen(0, peersWithInfo.length - 1).sample.get
          val sourcePeerId    = peersWithInfo(sourcePeerIndex).peer.id
          val newBlockEvents  = blocks.map(block => PeerEvent.MessageFromPeer(sourcePeerId, NewBlock(block)))
          val result          = processNewBlocks(new AlwaysSucceedAuthenticityValidator, peersWithInfo, newBlockEvents)

          if (peersWithInfo.length == 1) {
            // only one peer, no broadcast will ever occur
            result shouldBe empty
          } else {
            result.grouped(peersWithInfo.length - 1).zip(blocks).foreach { case (result, block) =>
              assertGossipedMessageToAllButSource(result, peersWithInfo, NewBlock(block), sourcePeerId)
            }
          }
        }
      }

      "ignore already valid gossiped block from the same source" in {
        forAll(Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen), obftBlockGen) { case (peersWithInfo, block) =>
          val sourcePeerIndex = Generators.intGen(0, peersWithInfo.length - 1).sample.get
          val sourcePeerId    = peersWithInfo(sourcePeerIndex).peer.id
          val newBlockEvent   = PeerEvent.MessageFromPeer(sourcePeerId, NewBlock(block))
          val result = processNewBlocks(
            new AlwaysSucceedAuthenticityValidator,
            peersWithInfo,
            Seq(newBlockEvent, newBlockEvent)
          )

          assertGossipedMessageToAllButSource(result, peersWithInfo, NewBlock(block), sourcePeerId)
        }

      }

      "ignore already valid gossiped block from another source" in {
        forAll(
          Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen),
          Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen),
          obftBlockGen
        ) { case (peersWithInfo1, peersWithInfo2, block) =>
          val sourcePeerIndex1     = Generators.intGen(0, peersWithInfo1.length - 1).sample.get
          val sourcePeerIndex2     = Generators.intGen(0, peersWithInfo2.length - 1).sample.get
          val allPeers             = peersWithInfo1 ++ peersWithInfo2
          val sourcePeerId1        = peersWithInfo1(sourcePeerIndex1).peer.id
          val sourcePeerId2        = peersWithInfo2(sourcePeerIndex2).peer.id
          val newBlockEventSource1 = PeerEvent.MessageFromPeer(sourcePeerId1, NewBlock(block))
          val newBlockEventSource2 = PeerEvent.MessageFromPeer(sourcePeerId2, NewBlock(block))
          val result = processNewBlocks(
            new AlwaysSucceedAuthenticityValidator,
            allPeers,
            Seq(newBlockEventSource1, newBlockEventSource2)
          )
          assertGossipedMessageToAllButSource(result, allPeers, NewBlock(block), sourcePeerId1)
        }
      }

      "ignore invalid non-gossiped block" in {
        forAll(Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen), obftBlockGen) { case (peersWithInfo, block) =>
          val sourcePeerIndex = Generators.intGen(0, peersWithInfo.length - 1).sample.get
          val sourcePeerId    = peersWithInfo(sourcePeerIndex).peer.id
          val newBlockEvent   = PeerEvent.MessageFromPeer(sourcePeerId, NewBlock(block))
          val result          = processNewBlocks(new AlwaysFailAuthenticityValidator, peersWithInfo, Seq(newBlockEvent))

          result shouldBe empty
        }
      }
    }

    class AlwaysSucceedAuthenticityValidator extends HeaderValidator[IO] {
      override def validate(header: ObftHeader): IO[Either[HeaderError, ObftHeader]] =
        header.asRight[HeaderError].pure[IO]
    }

    class AlwaysFailAuthenticityValidator extends HeaderValidator[IO] {
      override def validate(header: ObftHeader): IO[Either[HeaderValidator.HeaderError, ObftHeader]] =
        HeaderValidator.HeaderInvalidSignature(header).asLeft[ObftHeader].pure[IO]
    }

    def processNewBlocks(
        authenticityValidator: HeaderValidator[IO],
        peersWithInfo: List[PeerWithInfo],
        inputPeerEvents: Seq[PeerEvent]
    ): Seq[PeerAction] =
      (for {
        peersStateRef <- SignallingRef[IO, Map[PeerId, PeerWithInfo]](
                           peersWithInfo.map(peerWithInfo => peerWithInfo.peer.id -> peerWithInfo).toMap
                         )
        gossipPipe = BlockGossip[IO](
                       authenticityValidator,
                       stabilityParameter,
                       gossipCacheFactor,
                       peersStateRef
                     ).pipe
        result <- fs2.Stream.emits(inputPeerEvents).covary[IO].through(gossipPipe).compile.toVector
      } yield result).unsafeRunSync()

    def assertGossipedMessageToAllButSource(
        result: Seq[PeerAction],
        peersWithInfo: List[PeerWithInfo],
        expectedMessage: Message,
        sourcePeerId: PeerId
    ): Unit = {
      result.foreach(_.message should be(expectedMessage))
      result.collect { case PeerAction.MessageToPeer(peerId, _) =>
        peerId
      } should contain theSameElementsAs peersWithInfo.map(_.peer.id).filter(_ != sourcePeerId)

    }
  }
}
