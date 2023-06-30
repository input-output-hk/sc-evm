package io.iohk.scevm.domain

import io.iohk.ethereum.crypto.{AbstractPrivateKey, ECDSA}
import io.iohk.scevm.utils.SystemTime

sealed trait SlotEvent {
  def slot: Slot

  def timestamp: SystemTime.UnixTimestamp
}

/** The node is not leader of the slot */
final case class NotLeaderSlotEvent(slot: Slot, timestamp: SystemTime.UnixTimestamp) extends SlotEvent

/** The node is the leader of the slot */
final case class LeaderSlotEvent(
    slot: Slot,
    timestamp: SystemTime.UnixTimestamp,
    keySet: LeaderSlotEvent.KeySet
) extends SlotEvent {
  def pubKey: SidechainPublicKey  = keySet.pubKey
  def prvKey: SidechainPrivateKey = keySet.prvKey
}

object LeaderSlotEvent {
  final case class KeySet(
      pubKey: SidechainPublicKey,
      prvKey: SidechainPrivateKey,
      crossChainPrvKey: Option[AbstractPrivateKey[_]]
  )
  object KeySet {
    val ECDSAZero: KeySet = KeySet(
      ECDSA.PublicKey.Zero,
      ECDSA.PrivateKey.Zero,
      Some(ECDSA.PrivateKey.Zero)
    )
  }
}
