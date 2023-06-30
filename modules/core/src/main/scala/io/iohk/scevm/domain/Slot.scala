package io.iohk.scevm.domain

import cats.Show
import cats.syntax.all._
import io.iohk.scevm.serialization.Newtype

import scala.language.implicitConversions

final case class Slot(number: BigInt) extends AnyVal {
  def add(inc: BigInt): Slot = Slot(number + inc)
}

object Slot {
  implicit val show: Show[Slot]                    = cats.derived.semiauto.show
  implicit val ordering: Ordering[Slot]            = Ordering.by(_.number)
  implicit def ordered(other: Slot): Ordered[Slot] = Ordered.orderingToOrdered(other)(ordering)
  implicit val numeric: Numeric[Slot]              = Numeric[BigInt].imap(Slot(_))(_.number)

  implicit val valueClass: Newtype[Slot, BigInt] =
    Newtype[Slot, BigInt](Slot.apply, _.number)
}
