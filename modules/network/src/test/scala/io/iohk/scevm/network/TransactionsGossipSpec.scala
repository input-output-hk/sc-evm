package io.iohk.scevm.network

import cats.Id
import cats.data.NonEmptyList
import cats.effect.IO
import io.iohk.scevm.network.Generators.peerWithInfoGen
import io.iohk.scevm.network.PeerAction.MessageToPeer
import io.iohk.scevm.network.TransactionsImport.{NewTransactions, TransactionsFromPeer, TransactionsFromRpc}
import io.iohk.scevm.network.p2p.messages.OBFT1.{NewPooledTransactionHashes, SignedTransactions}
import io.iohk.scevm.testing.TransactionGenerators.signedTxGen
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TransactionsGossipSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  "TransactionsGossip" when {

    "gossipToAll" should {
      "broadcast message from RPC to all peers" in forAll(
        Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen),
        signedTxGen()
      ) { (peers, signedTransaction) =>
        val state          = peers.map(p => (p.id, p)).toMap
        val newTransaction = TransactionsFromRpc(NonEmptyList.one(signedTransaction))

        // Test
        val tested = fs2.Stream
          .emit[Id, (NewTransactions, Map[PeerId, PeerWithInfo])](
            (newTransaction, state)
          )
          .through(TransactionsGossip.gossipToAll[Id])
          .compile
          .toList

        tested.map(_.peerId) should contain theSameElementsAs peers.map(_.id)
        tested.count {
          case MessageToPeer(_, _: NewPooledTransactionHashes) => true
          case MessageToPeer(_, _: SignedTransactions)         => true
          case _                                               => false
        } shouldBe peers.size
      }

      "broadcast message from network to all peers but sender" in forAll(
        Gen.nonEmptyListOf[PeerWithInfo](peerWithInfoGen),
        signedTxGen()
      ) { (peers, signedTransaction) =>
        val state              = peers.map(p => (p.id, p)).toMap
        val newTransaction     = TransactionsFromPeer(peers.head.id, NonEmptyList.one(signedTransaction))
        val peersWithoutSender = peers.filterNot(_.id == newTransaction.from).map(_.id)

        val tested = fs2.Stream
          .emit[Id, (NewTransactions, Map[PeerId, PeerWithInfo])]((newTransaction, state))
          .through(TransactionsGossip.gossipToAll)
          .compile
          .toList

        // Test
        tested.map(_.peerId) should contain theSameElementsAs peersWithoutSender
        tested.count {
          case MessageToPeer(_, _: NewPooledTransactionHashes) => true
          case MessageToPeer(_, _: SignedTransactions)         => true
          case _                                               => false
        } shouldBe peersWithoutSender.size
      }
    }
  }
}
