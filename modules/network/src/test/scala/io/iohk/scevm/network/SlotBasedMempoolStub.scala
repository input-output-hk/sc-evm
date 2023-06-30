package io.iohk.scevm.network

import cats.syntax.all._
import cats.{Applicative, Traverse}
import io.iohk.scevm.domain.Slot
import io.iohk.scevm.storage.execution.SlotBasedMempool

import scala.collection.mutable.ListBuffer

final case class SlotBasedMempoolStub[F[_]: Applicative, A](inner: ListBuffer[A]) extends SlotBasedMempool[F, A] {

  override def add(elem: A, slot: Slot): F[SlotBasedMempool.AddResult[A]] =
    Applicative[F].pure {
      inner.addOne(elem)
      SlotBasedMempool.Added(elem, slot)
    }

  override def addAll[B[_]: Traverse](elems: B[A], slot: Slot): F[B[SlotBasedMempool.AddResult[A]]] =
    elems.traverse(add(_, slot))

  override def remove(elem: A): F[SlotBasedMempool.RemovalResult[A]] = Applicative[F].pure {
    inner.filterNot(elem == _)
    new SlotBasedMempool.Removed[A](elem)
  }

  override def removeAll[B[_]: Traverse](elems: B[A]): F[B[SlotBasedMempool.RemovalResult[A]]] = elems.traverse(remove)

  override def getAll: F[List[A]] = Applicative[F].pure(inner.toList)

  override def evict(slot: Slot): F[Unit] = Applicative[F].unit

  override def getAliveElements(slot: Slot): F[List[A]] = getAll
}
