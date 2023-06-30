package io.iohk.scevm.storage.execution

import cats.Traverse
import io.iohk.scevm.domain.Slot

trait SlotBasedMempool[F[_], A] {

  import SlotBasedMempool._

  def add(elem: A, slot: Slot): F[AddResult[A]]
  def addAll[B[_]: Traverse](elems: B[A], event: Slot): F[B[AddResult[A]]]
  def remove(elem: A): F[RemovalResult[A]]
  def removeAll[B[_]: Traverse](elems: B[A]): F[B[RemovalResult[A]]]

  /** @return all elements in the mempool
    */
  def getAll: F[List[A]]

  /** @return all elements with a valid TTL according to current slot. It's returning the same as evict(slot) >> getAll, without the eviction side-effect
    */
  def getAliveElements(slot: Slot): F[List[A]]

  def evict(slot: Slot): F[Unit]

}

object SlotBasedMempool {

  sealed trait AddResult[A]
  final case class Added[A](elem: A, slot: Slot)        extends AddResult[A]
  final case class NotAdded[A](elem: A, reason: String) extends AddResult[A]

  sealed trait RemovalResult[A]
  final case class Removed[A](elem: A)                    extends RemovalResult[A]
  final case class NotRemoved[A](elem: A, reason: String) extends RemovalResult[A]

}
