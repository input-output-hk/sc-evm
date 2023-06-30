package io.iohk.scevm.network

import cats.effect.IO
import fs2.concurrent.SignallingRef
import io.iohk.scevm.domain.{SignedTransaction, Slot}
import io.iohk.scevm.network.Generators.{peerGen, peerInfoGen}
import io.iohk.scevm.network.TransactionHandlerSpec.StubbedMempool
import io.iohk.scevm.network.p2p.messages.OBFT1
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.testing.TransactionGenerators.signedTxGen
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.mutable.ListBuffer

class TransactionHandlerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport {

  implicit val arbStxs: Arbitrary[Seq[SignedTransaction]] = Arbitrary(Gen.listOf(signedTxGen()))
  implicit val arbMempool: Arbitrary[StubbedMempool] = Arbitrary(
    arbStxs.arbitrary.map(txs => SlotBasedMempoolStub(ListBuffer.from(txs)))
  )
  implicit val arbPeer: Arbitrary[Peer]         = Arbitrary(peerGen)
  implicit val arbPeerInfo: Arbitrary[PeerInfo] = Arbitrary(peerInfoGen)

  "TransactionsHandler" when {
    "receive new pooled transaction hashes" should {
      "request for any missing transactions" in {
        forAll { (mempool: StubbedMempool, newStxs: Seq[SignedTransaction], peer: Peer) =>
          forAll(Gen.someOf(mempool.inner)) { oldStxs =>
            val hashes = (oldStxs ++ newStxs).map(_.hash).toSeq
            assertIO {
              for {
                currentSlotRef <- createRefWithInitalSlot
                transactionsImportService =
                  new TransactionsImportServiceImpl(mempool, currentSlotRef)
                test <-
                  fs2
                    .Stream(PeerEvent.MessageFromPeer(peer.id, OBFT1.NewPooledTransactionHashes(hashes)))
                    .through(
                      TransactionsHandler
                        .pipe(mempool, transactionsImportService, fs2.Stream[IO, Map[PeerId, PeerWithInfo]](Map.empty))
                    )
                    .compile
                    .toList
                    .map {
                      case List(PeerAction.MessageToPeer(peerId, OBFT1.GetPooledTransactions(_, requested))) =>
                        peerId shouldBe peer.id
                        requested shouldBe newStxs.map(_.hash)
                      case Nil    => newStxs shouldBe Nil
                      case result => fail(s"Invalid result: $result")
                    }
              } yield test
            }
          }
        }
      }
    }

    "receive pooled transactions" should {
      "be published to the topic" in {
        forAll { (stxs: Seq[SignedTransaction], peer: Peer, requestId: Long) =>
          val mempool = SlotBasedMempoolStub[IO, SignedTransaction](ListBuffer.empty)
          assertIO {
            for {
              currentSlotRef <- createRefWithInitalSlot
              transactionsImportService =
                new TransactionsImportServiceImpl(mempool, currentSlotRef)
              _ <- fs2
                     .Stream(PeerEvent.MessageFromPeer(peer.id, OBFT1.PooledTransactions(RequestId(requestId), stxs)))
                     .through(
                       TransactionsHandler
                         .pipe(mempool, transactionsImportService, fs2.Stream[IO, Map[PeerId, PeerWithInfo]](Map.empty))
                     )
                     .compile
                     .drain
              addedStxs <- mempool.getAll
            } yield addedStxs should contain theSameElementsAs stxs
          }
        }
      }
    }

    "peer handshake successful" should {
      "respond with exchanging mempool hashes" in {
        forAll { (mempool: StubbedMempool, peer: Peer, peerInfo: PeerInfo) =>
          assertIO {
            for {
              currentSlotRef <- createRefWithInitalSlot
              transactionsImportService =
                new TransactionsImportServiceImpl(mempool, currentSlotRef)
              test <-
                fs2
                  .Stream(PeerEvent.PeerHandshakeSuccessful(peer, peerInfo))
                  .through(
                    TransactionsHandler
                      .pipe(mempool, transactionsImportService, fs2.Stream[IO, Map[PeerId, PeerWithInfo]](Map.empty))
                  )
                  .compile
                  .toList
                  .map {
                    case List(PeerAction.MessageToPeer(peerId, OBFT1.NewPooledTransactionHashes(hashes))) =>
                      peerId shouldBe peer.id
                      hashes shouldBe mempool.inner.map(_.hash)
                    case result => fail(s"Invalid result: $result")
                  }
            } yield test
          }
        }
      }
    }
  }

  private def createRefWithInitalSlot =
    SignallingRef.of[IO, Slot](Slot(1))
}

object TransactionHandlerSpec {
  type StubbedMempool = SlotBasedMempoolStub[IO, SignedTransaction]
}
