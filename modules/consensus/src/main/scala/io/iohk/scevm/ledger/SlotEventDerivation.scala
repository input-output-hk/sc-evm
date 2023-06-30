package io.iohk.scevm.ledger

import cats.Monad
import cats.data.EitherT
import cats.syntax.all._
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.consensus.pos.PoSConfig
import io.iohk.scevm.domain._
import org.typelevel.log4cats.LoggerFactory

object SlotEventDerivation {

  /** Convert Tick to SlotEvent
    * @param privateKeySets of the node to sign blocks and cross-chain data in sidechain mode:
    *                         - 0 means the node is not a block producer
    *                         - 1 means the node is a block producer
    *                         - more than 1 is used for data-generation while testing
    */
  def tickToSlotEvent[F[_]: Monad: LoggerFactory](
      leaderElection: LeaderElection[F]
  )(privateKeySets: List[PoSConfig.KeySet])(tick: Tick): F[SlotEvent] = {
    val keyPairs = privateKeySets.map(slotEventKeySetFromConfiguration)

    EitherT(leaderElection.getSlotLeader(tick.slot))
      .leftSemiflatTap(failure => LoggerFactory.getLogger.error(show"Could not determine slot leader because $failure"))
      .toOption
      .subflatMap(leaderPubKey => keyPairs.find(_.pubKey == leaderPubKey))
      .flatTapNone(
        LoggerFactory.getLogger.info(show"This node is not the leader of ${tick.slot}: no block generated")
      )
      .fold[SlotEvent](default = NotLeaderSlotEvent(tick.slot, tick.timestamp))(keySet =>
        LeaderSlotEvent(tick.slot, tick.timestamp, keySet)
      )
  }

  def slotEventKeySetFromConfiguration(privateKeys: PoSConfig.KeySet): LeaderSlotEvent.KeySet =
    LeaderSlotEvent.KeySet(
      pubKey = ECDSA.PublicKey.fromPrivateKey(privateKeys.leaderPrvKey),
      prvKey = privateKeys.leaderPrvKey,
      crossChainPrvKey = privateKeys.crossChainPrvKey
    )

}
