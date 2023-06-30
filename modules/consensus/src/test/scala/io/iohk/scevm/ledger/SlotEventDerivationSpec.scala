package io.iohk.scevm.ledger

import cats.effect.IO
import cats.syntax.all._
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.consensus.pos.PoSConfig
import io.iohk.scevm.domain._
import io.iohk.scevm.testing.{CryptoGenerators, Generators, IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.noop.NoOpFactory

class SlotEventDerivationSpec
    extends AnyWordSpec
    with IOSupport
    with ScalaFutures
    with NormalPatience
    with ScalaCheckPropertyChecks
    with Matchers {
  implicit val _logger: SelfAwareStructuredLogger[IO] = NoOpFactory.getLogger[IO]

  def listOfExactlyNKeySets(n: Int): Gen[List[PoSConfig.KeySet]] = CryptoGenerators
    .listOfExactlyNGen(n)
    .map {
      _.map { case (prvKey, _) =>
        PoSConfig.KeySet(prvKey, Some(prvKey))
      }
    }

  "tickToSlotEvent" should {
    "return a LeaderSlotEvent if leading" in
      forAll(Generators.anyTickGen, listOfExactlyNKeySets(10)) { case (keySets, list) =>
        val leaderKeySet = SlotEventDerivation.slotEventKeySetFromConfiguration(list.head) // is one of "our" keys

        val alwaysLeadingElection = new LeaderElection[IO] {
          override def getSlotLeader(slot: Slot): IO[Either[LeaderElection.ElectionFailure, ECDSA.PublicKey]] =
            Either.right(leaderKeySet.pubKey).pure[IO]
        }

        val slotEvent = SlotEventDerivation
          .tickToSlotEvent(alwaysLeadingElection)(list)(keySets)
          .ioValue

        assert(
          slotEvent == LeaderSlotEvent(
            keySets.slot,
            keySets.timestamp,
            leaderKeySet
          )
        )
      }

    "return a NotLeaderSlotEvent if not leading" in
      forAll(Generators.anyTickGen, listOfExactlyNKeySets(10)) { case (tick, list) =>
        val leaderKeySet = SlotEventDerivation.slotEventKeySetFromConfiguration(list.head)
        val privateKeys  = list.tail // node keys do not contain the leader key

        val notLeadingElection = new LeaderElection[IO] {
          override def getSlotLeader(slot: Slot): IO[Either[LeaderElection.ElectionFailure, ECDSA.PublicKey]] =
            Either.right(leaderKeySet.pubKey).pure[IO]
        }

        val slotEvent = SlotEventDerivation
          .tickToSlotEvent(notLeadingElection)(privateKeys)(tick)
          .ioValue

        assert(slotEvent == NotLeaderSlotEvent(tick.slot, tick.timestamp))
      }

    "return a NotLeaderSlotEvent if empty list" in
      forAll(Generators.anyTickGen, CryptoGenerators.ecdsaPublicKeyGen) { case (tick, leaderPubKey) =>
        val privateKeys = List.empty[PoSConfig.KeySet]

        val notLeadingElection = new LeaderElection[IO] {
          override def getSlotLeader(slot: Slot): IO[Either[LeaderElection.ElectionFailure, ECDSA.PublicKey]] =
            Either.right(leaderPubKey).pure[IO]
        }

        val slotEvent = SlotEventDerivation
          .tickToSlotEvent(notLeadingElection)(privateKeys)(tick)
          .ioValue

        assert(slotEvent == NotLeaderSlotEvent(tick.slot, tick.timestamp))
      }

    "return a NotLeaderSlotEvent if error during election" in
      forAll(Generators.anyTickGen, listOfExactlyNKeySets(10)) { case (tick, keySets) =>
        val erroneousElection = new LeaderElection[IO] {
          override def getSlotLeader(slot: Slot): IO[Either[LeaderElection.ElectionFailure, ECDSA.PublicKey]] =
            Either.left(LeaderElection.RequirementsNotMet("")).pure[IO]
        }

        val slotEvent = SlotEventDerivation
          .tickToSlotEvent(erroneousElection)(keySets)(tick)
          .ioValue

        assert(slotEvent == NotLeaderSlotEvent(tick.slot, tick.timestamp))
      }
  }

}
